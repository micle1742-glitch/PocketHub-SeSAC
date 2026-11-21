package com.pockethub.file.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Stream;

/** 로컬 디스크 저장소: {upload.dir}/{key} 에 저장한다 (기존 동작) */
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(String uploadDir) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public void store(String key, MultipartFile file) throws IOException {
        Path target = resolve(key);
        if (target == null) {
            throw new IllegalArgumentException("파일 저장 위치가 올바르지 않습니다.");
        }
        Files.createDirectories(target.getParent());
        file.transferTo(target);
    }

    @Override
    public boolean read(String key, StreamConsumer consumer) throws IOException {
        Path path = resolve(key);
        if (path == null || !Files.isRegularFile(path)) return false;
        try (InputStream in = Files.newInputStream(path)) {
            consumer.accept(in);
        }
        return true;
    }

    @Override
    public void delete(Collection<String> keys) throws IOException {
        for (String key : keys) {
            Path path = resolve(key);
            if (path == null) continue;
            Files.deleteIfExists(path);
            deleteIfEmpty(path.getParent());  // 커밋 폴더가 비면 정리
        }
    }

    @Override
    public void deleteRepository(long repoId, Collection<String> keys) throws IOException {
        // 기존처럼 팀 폴더({upload.dir}/{repoId}) 를 통째로 지운다. 예전 형식의 파일도 함께 정리된다
        Path repoDir = root.resolve(String.valueOf(repoId)).normalize();
        if (!repoDir.startsWith(root) || !Files.exists(repoDir)) return;
        try (Stream<Path> walk = Files.walk(repoDir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Override
    public Path localPath(String key) {
        return resolve(key);
    }

    /** key 를 실제 경로로 바꾼다. 비어 있거나 저장 폴더 밖을 가리키면 null */
    private Path resolve(String key) {
        if (key == null || key.isBlank()) return null;
        Path path = root.resolve(key).normalize();
        return path.startsWith(root) && !path.equals(root) ? path : null;
    }

    private void deleteIfEmpty(Path dir) {
        if (dir == null || dir.equals(root) || !dir.startsWith(root)) return;
        try {
            Files.deleteIfExists(dir);
        } catch (DirectoryNotEmptyException ignored) {
            // 다른 파일이 남아 있으면 그대로 둔다
        } catch (IOException ignored) {
            // 빈 폴더 정리는 실패해도 동작에 영향이 없다
        }
    }
}
