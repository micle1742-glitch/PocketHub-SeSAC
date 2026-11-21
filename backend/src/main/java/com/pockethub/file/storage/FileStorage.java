package com.pockethub.file.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;

/**
 * 업로드 파일 저장소. 키(file.file_path 에 저장되는 값) 단위로 저장·읽기·삭제한다.
 * 구현: LocalFileStorage(로컬 디스크), SupabaseStorageClient(Supabase Storage)
 * 어떤 구현을 쓸지는 FileStorageConfig 가 storage.type 설정으로 정한다.
 */
public interface FileStorage {

    /** 파일 내용을 key 위치에 저장한다 */
    void store(String key, MultipartFile file) throws IOException;

    /**
     * key 의 파일 내용을 consumer 에 넘긴다. 파일이 없으면 consumer 를 부르지 않고 false 를 돌려준다.
     * 스트림은 consumer 가 끝나면 이 메서드가 닫는다.
     */
    boolean read(String key, StreamConsumer consumer) throws IOException;

    /** key 들의 파일을 지운다. 없는 key 는 무시한다 */
    void delete(Collection<String> keys) throws IOException;

    /** 팀(저장소)을 지울 때 그 팀의 파일을 모두 지운다. keys 는 DB 에 기록된 그 팀의 파일 키 목록 */
    void deleteRepository(long repoId, Collection<String> keys) throws IOException;

    /** 로컬 디스크 저장소면 key 의 실제 경로(복사 없이 바로 전송할 때 사용), 아니면 null */
    default Path localPath(String key) {
        return null;
    }

    @FunctionalInterface
    interface StreamConsumer {
        void accept(InputStream in) throws IOException;
    }
}
