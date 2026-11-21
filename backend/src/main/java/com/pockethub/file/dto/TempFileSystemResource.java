package com.pockethub.file.dto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;

// 전송용 임시 파일(Storage 에서 받은 파일, 커밋 Zip)을 보낸 뒤 지우기 위해 FileSystemResource 를 상속받아 구현
@Slf4j
public class TempFileSystemResource extends FileSystemResource {
    private final File file;

    public TempFileSystemResource(File file) {
        super(file);
        this.file = file;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new FilterInputStream(new FileInputStream(file)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    deleteTempFile();
                }
            }
        };
    }

    // 전송 방식에 따라 채널로 읽을 수도 있어서 같은 방식으로 지운다
    @Override
    public ReadableByteChannel readableChannel() throws IOException {
        ReadableByteChannel channel = super.readableChannel();
        return new ReadableByteChannel() {
            @Override
            public int read(java.nio.ByteBuffer dst) throws IOException {
                return channel.read(dst);
            }

            @Override
            public boolean isOpen() {
                return channel.isOpen();
            }

            @Override
            public void close() throws IOException {
                try {
                    channel.close();
                } finally {
                    deleteTempFile();
                }
            }
        };
    }

    // 응답은 이미 나간 뒤라 예외를 던지지 않고 경고만 남긴다 (파일 경로·이름은 로그에 넣지 않음)
    private void deleteTempFile() {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warn("전송한 임시 파일을 삭제하지 못했습니다. ({})", e.getClass().getSimpleName());
        }
    }
}
