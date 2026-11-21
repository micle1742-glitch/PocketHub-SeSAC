package com.pockethub.file.storage;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Supabase Storage REST API 저장소 (비공개 버킷, service key 사용)
 *   업로드: POST   {url}/storage/v1/object/{bucket}/{key}
 *   다운로드: GET  {url}/storage/v1/object/{bucket}/{key}
 *   삭제: DELETE   {url}/storage/v1/object/{bucket}  body {"prefixes": [key, ...]}
 * service key 는 요청 헤더(apikey, Authorization)에만 쓰고 로그·예외 메시지에 넣지 않는다.
 */
public class SupabaseStorageClient implements FileStorage {

    // 키는 {repoId}/{commitId}/{UUID} 형식. 영문·숫자·/ _ . - 만 허용 (URL 인코딩이 필요 없게)
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9/_.-]*");
    private static final Pattern BUCKET_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final int DELETE_BATCH_SIZE = 1000;
    private static final int ERROR_BODY_LIMIT = 2048;

    private final RestClient restClient;
    private final String objectBaseUrl;  // {url}/storage/v1/object/{bucket}

    public SupabaseStorageClient(String url, String serviceKey, String bucket) {
        if (!BUCKET_PATTERN.matcher(bucket).matches()) {
            throw new IllegalArgumentException("SUPABASE_BUCKET 값 형식이 올바르지 않습니다.");
        }
        this.objectBaseUrl = url.replaceAll("/+$", "") + "/storage/v1/object/" + bucket;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("apikey", serviceKey)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceKey)
                .build();
    }

    @Override
    public void store(String key, MultipartFile file) throws IOException {
        URI uri = objectUri(key);
        if (uri == null) {
            throw new IllegalArgumentException("파일 저장 위치가 올바르지 않습니다.");
        }
        MediaType contentType = parseContentType(file.getContentType());
        byte[] body = file.getBytes();  // 파일 1개 최대 10MB (spring.servlet.multipart.max-file-size)

        try {
            restClient.post()
                    .uri(uri)
                    .contentType(contentType)
                    .header("x-upsert", "false")
                    .body(body)
                    .exchange((request, response) -> {
                        ensureSuccess(response.getStatusCode(), "업로드");
                        return null;
                    });
        } catch (RestClientException e) {
            throw new IOException("파일 저장소에 연결하지 못했습니다. (업로드)");
        }
    }

    @Override
    public boolean read(String key, StreamConsumer consumer) throws IOException {
        URI uri = objectUri(key);
        if (uri == null) return false;  // 예전 형식(파일명이 들어간 키 등)은 Storage 에 없다

        try {
            Boolean found = restClient.get()
                    .uri(uri)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 404) return false;
                        if (status.value() == 400 && isNotFoundBody(response.getBody())) return false;
                        ensureSuccess(status, "다운로드");
                        try (InputStream in = response.getBody()) {
                            consumer.accept(in);
                        }
                        return true;
                    });
            return Boolean.TRUE.equals(found);
        } catch (RestClientException e) {
            throw new IOException("파일 저장소에 연결하지 못했습니다. (다운로드)");
        }
    }

    @Override
    public void delete(Collection<String> keys) throws IOException {
        List<String> valid = keys.stream()
                .filter(k -> k != null && isValidKey(k))
                .distinct()
                .toList();

        for (int i = 0; i < valid.size(); i += DELETE_BATCH_SIZE) {
            List<String> batch = new ArrayList<>(valid.subList(i, Math.min(i + DELETE_BATCH_SIZE, valid.size())));
            try {
                restClient.method(HttpMethod.DELETE)
                        .uri(URI.create(objectBaseUrl))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("prefixes", batch))
                        .exchange((request, response) -> {
                            ensureSuccess(response.getStatusCode(), "삭제");
                            return null;
                        });
            } catch (RestClientException e) {
                throw new IOException("파일 저장소에 연결하지 못했습니다. (삭제)");
            }
        }
    }

    @Override
    public void deleteRepository(long repoId, Collection<String> keys) throws IOException {
        // Storage 는 폴더 단위 삭제가 없어서 DB 에 기록된 키를 하나씩 지운다
        delete(keys);
    }

    private URI objectUri(String key) {
        return isValidKey(key) ? URI.create(objectBaseUrl + "/" + key) : null;
    }

    private static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches() && !key.contains("..") && !key.contains("//");
    }

    private static void ensureSuccess(HttpStatusCode status, String action) throws IOException {
        if (!status.is2xxSuccessful()) {
            // 응답 본문·요청 헤더는 넣지 않는다 (상태 코드만)
            throw new IOException("파일 저장소 " + action + " 요청이 실패했습니다. (HTTP " + status.value() + ")");
        }
    }

    /** Supabase 는 없는 객체를 400 + {"statusCode":"404","error":"not_found"} 로 돌려주기도 한다 */
    private static boolean isNotFoundBody(InputStream body) {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(ERROR_BODY_LIMIT);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.contains("not_found") || text.contains("\"404\"") || text.contains("Object not found");
        } catch (IOException e) {
            return false;
        }
    }

    private static MediaType parseContentType(String value) {
        if (value == null || value.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
