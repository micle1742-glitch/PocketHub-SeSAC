package com.pockethub.file.dto;

import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.nio.charset.StandardCharsets;

@Getter
@Setter
public class FileDownloadRes {
    private String fileName;
    private String mediaType;
    private String fileLength;
    private File file;
    // true 면 전송용으로 만든 임시 파일 → 보낸 뒤 지운다 (TempFileSystemResource)
    private boolean temporary;

    public FileDownloadRes() { }

    public FileDownloadRes(String fileName, String mediaType, String fileLength, File file) {
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.fileLength = fileLength;
        this.file = file;
    }

    /**
     * Content-Disposition 헤더 값: attachment; filename="ASCII 대체 이름"; filename*=UTF-8''퍼센트 인코딩
     * filename* (RFC 5987/6266) 를 지원하는 브라우저는 한글 파일명을 그대로 받고, 아니면 filename 을 쓴다
     */
    public String contentDisposition() {
        String name = (fileName == null || fileName.isBlank()) ? "download" : fileName;
        return "attachment; filename=\"" + asciiFallback(name) + "\"; filename*=UTF-8''" + encodeRfc5987(name);
    }

    // ASCII 가 아닌 문자, 따옴표, 역슬래시, 제어 문자는 '_' 로 바꾼다
    private static String asciiFallback(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
        }
        return sb.toString();
    }

    // RFC 5987 attr-char(영문·숫자·!#$&+-.^_`|~) 외에는 UTF-8 바이트를 %XX 로 인코딩
    private static String encodeRfc5987(String name) {
        StringBuilder sb = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }
}
