package com.pockethub.file.service;

import com.pockethub.commit.dto.CommitDTO;
import com.pockethub.common.exception.ConflictException;
import com.pockethub.common.exception.FileNotCleanupException;
import com.pockethub.common.exception.FileNotCreatedException;
import com.pockethub.common.exception.NotFoundException;
import com.pockethub.file.domain.File;
import com.pockethub.file.dto.FileDTO;
import com.pockethub.file.dto.FileDownloadRes;
import com.pockethub.file.dto.FileState;
import com.pockethub.file.repository.FileRepository;
import com.pockethub.file.storage.FileStorage;
import com.pockethub.pr.domain.Pr;
import com.pockethub.pr.domain.PrState;
import com.pockethub.pr.repository.PrRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FileService {
    private final FileRepository fileRepository;
    private final PrRepository prRepository;

    // 실제 파일 저장 위치 (로컬 디스크 또는 Supabase Storage). FileStorageConfig 참고
    private final FileStorage fileStorage;

    public FileService(FileRepository fileRepository, PrRepository prRepository, FileStorage fileStorage) {
        this.fileRepository = fileRepository;
        this.prRepository = prRepository;
        this.fileStorage = fileStorage;
    }

    // 다중 파일 업로드
    @Transactional
    public List<FileDTO> uploadFiles(long repoId, String commitId, List<MultipartFile> files) {
        List<FileDTO> fileInfo = new ArrayList<>();

        // 다중 파일 업로드 -> 하면서 FileDTO 만들어 채우기
        try {
            for (MultipartFile file : files) {
                FileDTO info = uploadFile(repoId, commitId, file);
                fileInfo.add(info);
            }
        } catch(IOException e) {
            cleanupSavedFiles(fileInfo, e);
            throw new FileNotCreatedException("파일 업로드 도중 문제가 발생했습니다.");
        } catch(RuntimeException e) {
            // 중간에 실패하면 이미 저장소에 올린 파일을 지운다
            cleanupSavedFiles(fileInfo, e);
            throw e;
        }

        return fileInfo;
    }

    // 파일 업로드
    // 저장소 키(file_path) = {repoId}/{commitId}/{UUID}. 원본 파일명은 original_name 에만 저장한다
    // (한글 등 파일명을 키에 쓰면 Supabase Storage 가 거부할 수 있어서)
    @Transactional
    public FileDTO uploadFile(long repoId, String commitId, MultipartFile file) throws IOException {
        validateFile(file);

        String fileName = StringUtils.cleanPath(file.getOriginalFilename());
        if(fileName.contains("..")) {
            throw new IllegalArgumentException("파일명에 올바르지 않은 경로가 포함되어 있습니다.");
        }
        fileName = Paths.get(fileName).getFileName().toString();

        String storageKey = repoId + "/" + commitId + "/" + UUID.randomUUID();

        FileDTO info = new FileDTO(fileName, storageKey, commitId, repoId, file.getSize() / 1024.0f);

        File prevFile = fileRepository.findFirstByOriginalNameAndRepoIdAndIsLatestOrderByFileIdDesc(info.getFileName(), repoId, true);
        info.setIsLatest(true);
        if(prevFile != null) {
            prevFile.setIsLatest(false);
            if(prevFile.getState().equals(FileState.DELETE)) {
                info.setState(FileState.ADD);
            } else {
                info.setState(FileState.UPDATE);
                info.setPrevId(prevFile.getFileId());
            }
        } else {
            info.setState(FileState.ADD);
        }

        // 저장은 마지막에: 이 뒤로는 실패할 일이 없어서, 저장된 파일은 항상 호출한 쪽 목록에 들어간다
        fileStorage.store(storageKey, file);

        return info;
    }

    @Transactional
    public FileDTO deleteFile(long repoId, String commitId, long fileId) {
        File prevFile = fileRepository.findByFileIdAndRepoId(fileId, repoId);
        if(prevFile == null) {
            throw new NotFoundException("삭제하려는 파일이 존재하지 않습니다.");
        }
        if(!prevFile.getIsLatest()) {
            throw new ConflictException("삭제하려는 파일이 최신 파일이 아닙니다. 최신 파일을 선택해주세요.");
        }
        if(prevFile.getState().equals(FileState.DELETE)) {
            throw new ConflictException("삭제하려는 파일은 이미 삭제되어 있습니다.");
        }

        prevFile.setIsLatest(false);

        FileDTO dto = new FileDTO(prevFile.getOriginalName(), "", commitId, repoId, 0);
        dto.setIsLatest(true);
        dto.setPrevId(prevFile.getFileId());
        dto.setState(FileState.DELETE);

        return dto;
    }

    // 커밋 실패 시, 이번 요청에서 저장소에 올린 파일을 지운다 (file_path = 저장소 키)
    public void cleanupSavedFiles(List<FileDTO> savedFiles, Exception originalException) {
        List<String> keys = savedFiles.stream()
                .map(FileDTO::getFilePath)
                .filter(StringUtils::hasText)
                .toList();
        if(keys.isEmpty()) return;

        try {
            fileStorage.delete(keys);
        } catch (IOException | RuntimeException e) {
            originalException.addSuppressed(new FileNotCleanupException("저장된 파일을 정리하는 도중 문제가 발생했습니다."));
        }
    }

    // 팀 삭제 전에 호출: 그 팀의 파일 저장소 키 목록 (DB 의 file 행이 지워지기 전에 읽어 둬야 한다)
    @Transactional(readOnly = true)
    public List<String> findStorageKeys(long repoId) {
        return fileRepository.findFilePathsByRepoId(repoId);
    }

    // 팀 삭제 후 호출: 그 팀의 파일을 저장소에서 지운다
    public void deleteTeamFiles(long repoId, List<String> storageKeys) throws IOException {
        fileStorage.deleteRepository(repoId, storageKeys);
    }

    // 파일 유효성 검사
    private void validateFile(MultipartFile file) {
        if(file == null || file.isEmpty()) {
            throw new IllegalArgumentException("업로드할 파일이 비어있습니다.");
        }

        if(file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new IllegalArgumentException("원본 파일명이 없습니다.");
        }
    }

    @Transactional(readOnly = true)
    public FileDownloadRes fileDownload(long fileId, long repoId) {
        // 조회에서 repository 에 find 추가해놨으니 머지되면 가져와서 사용 교체
        File file = fileRepository.findByFileIdAndRepoId(fileId, repoId);
        if(file == null) {
            throw new NotFoundException("파일을 찾을 수 없습니다.");
        }

        // 삭제 기록(DELETE) 행은 file_path 가 비어 있다
        String storageKey = file.getFilePath();
        if(!StringUtils.hasText(storageKey)) {
            throw new NotFoundException("파일을 찾을 수 없습니다.");
        }

        FileDownloadRes res = new FileDownloadRes();
        res.setFileName(file.getOriginalName());  // 다운로드 파일명은 원본 파일명(original_name)

        // 로컬 디스크 저장소면 복사 없이 그 파일을 바로 보낸다
        Path localPath = fileStorage.localPath(storageKey);
        if(localPath != null) {
            if(!Files.isRegularFile(localPath)) {
                throw new NotFoundException("파일을 찾을 수 없습니다.");
            }
            res.setFile(localPath.toFile());
            res.setTemporary(false);
            return res;
        }

        // 원격 저장소면 임시 파일로 받아서 보낸 뒤 지운다 (TempFileSystemResource)
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("pockethub-file-", ".download");
            Path target = tempFile;
            boolean found = fileStorage.read(storageKey,
                    in -> Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING));
            if(!found) {
                deleteQuietly(tempFile);
                throw new NotFoundException("파일을 찾을 수 없습니다.");
            }
        } catch (IOException e) {
            deleteQuietly(tempFile);
            throw new FileNotCreatedException("파일을 불러오는 도중 문제가 발생했습니다.");
        }

        res.setFile(tempFile.toFile());
        res.setTemporary(true);
        return res;
    }

    // 커밋의 파일을 저장소에서 받아 임시 Zip 파일로 묶는다. 전송 후 TempFileSystemResource 가 지운다
    public FileDownloadRes fileDownload(CommitDTO commitDTO) {
        FileDownloadRes res = new FileDownloadRes();
        int count = 0;
        Path zipPath = null;

        try {
            // 요청마다 다른 임시 파일 (같은 커밋을 동시에 받아도 겹치지 않게)
            zipPath = Files.createTempFile("pockethub-commit-", ".zip");

            try(OutputStream os = Files.newOutputStream(zipPath);
                ZipOutputStream zos = new ZipOutputStream(os)) {

                Set<String> usedNames = new HashSet<>();

                for(FileDTO fileDTO : commitDTO.getFiles()) {
                    if(fileDTO.getState().equals(FileState.DELETE)) continue;
                    if(!StringUtils.hasText(fileDTO.getFilePath())) continue;

                    String entryName = uniqueEntryName(fileDTO.getFileName(), usedNames);
                    boolean found = fileStorage.read(fileDTO.getFilePath(), in -> {
                        zos.putNextEntry(new ZipEntry(entryName));
                        in.transferTo(zos);
                        zos.closeEntry();
                    });
                    if(!found) {
                        usedNames.remove(entryName);
                        continue;
                    }

                    count++;
                }
            }

            if(count == 0) {
                deleteQuietly(zipPath);
                throw new NotFoundException("해당 Commit 에 다운로드 가능한 파일이 존재하지 않습니다.");
            }

            res.setFileName(commitDTO.getCommitId() + ".zip");
            res.setFile(zipPath.toFile());
            res.setTemporary(true);
        } catch (IOException e) {
            deleteQuietly(zipPath);
            throw new FileNotCreatedException("임시 파일 생성 중 문제가 발생했습니다.");
        }

        return res;
    }

    // 같은 커밋에 같은 이름 파일이 둘 이상이면 Zip 항목 이름이 겹쳐 오류가 나므로 "이름 (2).확장자" 로 바꾼다
    private static String uniqueEntryName(String fileName, Set<String> usedNames) {
        String name = StringUtils.hasText(fileName) ? fileName : "file";
        if(usedNames.add(name)) return name;

        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for(int i = 2; ; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if(usedNames.add(candidate)) return candidate;
        }
    }

    private static void deleteQuietly(Path path) {
        if(path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 임시 파일 정리 실패는 응답에 영향을 주지 않는다
        }
    }

    @Transactional(readOnly = true)
    public List<FileDTO> searchLatestFiles(long repoId) {
        // 상태가 DELETE 가 아닌 최신 파일들을 가져온다.
        List<File> files = fileRepository.searchLatestFiles(repoId);

        return files.stream().map(FileDTO::new).toList();
    }

    @Transactional(readOnly = true)
    public FileDTO findFileDetail(long fileId, long repoId) {
        File file = fileRepository.findByFileIdAndRepoId(fileId, repoId);

        if(file == null) {
            throw new NotFoundException("조회하려는 파일 정보가 존재하지 않습니다.");
        }

        return new FileDTO(file);
    }

    // 작업이 취소되지 않을 정도의 오류가 있을 경우 메세지만 반환
    @Transactional
    public StringBuilder revertLatestFile(List<String> commitIds) {
        StringBuilder errMsg = null;

        List<File> files = prRepository.findFilesByCommitIds(commitIds);
        for(int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            // 1. 최신 파일인가 체크
            if(!file.getIsLatest()) continue;
            file.setIsLatest(false);

            // 2. 최신 파일일 경우 이전 상태 파일이 존재하는가 확인
            if(file.getPrevId() != null) {
                File prevFile = fileRepository.findById(file.getPrevId())
                        .orElse(null);
                if(prevFile == null) {
                    if(errMsg == null) errMsg = new StringBuilder();
                    errMsg.append(file.getOriginalName()).append("의 기록된 이전 파일이 존재하지 않습니다. ");
                    continue;
                }

                if(prevFile.getCommit().getPrId() == null) {
                    prevFile.setIsLatest(true);
                    continue;
                }

                // 3. 이전 파일의 PR 승인 여부 상태 확인
                Pr pr = prRepository.findById(prevFile.getCommit().getPrId())
                        .orElse(null);
                if(pr == null) {
                    if(errMsg == null) errMsg = new StringBuilder();
                    errMsg.append(file.getOriginalName()).append("의 기록된 이전 파일을 저장한 PR이 존재하지 않습니다. ");
                    continue;
                }

                // 4. 이전 파일이 승인 되어 있으면 최신 파일 상태로 바꾸고 종료
                // 5. 이전 파일이 거부 되어 있으면 체크해야하는 파일 목록에 추가 (체크되기 위해서 최신 상태를 true 로 임시 바꿈)
                prevFile.setIsLatest(true);

                if(pr.getState().equals(PrState.DENIED)) {
                    files.add(prevFile);
                }
            }
        }

        return errMsg;
    }
}
