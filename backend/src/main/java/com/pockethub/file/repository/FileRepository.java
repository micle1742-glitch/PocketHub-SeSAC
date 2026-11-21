package com.pockethub.file.repository;

import com.pockethub.file.domain.File;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FileRepository extends JpaRepository<File, Long> {
    File findFirstByOriginalNameAndRepoIdAndIsLatestOrderByFileIdDesc(String originalName, long repoId, boolean isLatest);

    File findByFileIdAndRepoId(long fileId, long repoId);

    @Query("SELECT f FROM File f WHERE f.repoId = :repoId AND f.isLatest = true AND f.state != 'DELETE'" +
            "ORDER BY f.fileId DESC")
    List<File> searchLatestFiles(@Param("repoId") long repoId);

    // 팀 삭제 시 저장소에서 지울 파일 키 목록 (삭제 기록 행은 file_path 가 비어 있어 제외)
    @Query("SELECT f.filePath FROM File f WHERE f.repoId = :repoId AND f.filePath <> ''")
    List<String> findFilePathsByRepoId(@Param("repoId") long repoId);
}
