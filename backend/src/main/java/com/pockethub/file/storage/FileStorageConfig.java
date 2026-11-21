package com.pockethub.file.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 파일 저장소 선택 (application.yml 의 storage.type)
 *   local    : 로컬 디스크 (upload.dir)
 *   supabase : Supabase Storage (SUPABASE_URL, SUPABASE_SERVICE_KEY, SUPABASE_BUCKET 필요)
 *   비어 있음 : SUPABASE_* 세 값이 모두 있으면 supabase, 하나라도 없으면 local
 */
@Slf4j
@Configuration
public class FileStorageConfig {

    @Bean
    public FileStorage fileStorage(
            @Value("${storage.type:}") String type,
            @Value("${upload.dir:uploads}") String uploadDir,
            @Value("${supabase.url:}") String supabaseUrl,
            @Value("${supabase.service-key:}") String supabaseServiceKey,
            @Value("${supabase.bucket:}") String supabaseBucket
    ) {
        String mode = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);

        List<String> missing = new ArrayList<>();
        if (isBlank(supabaseUrl)) missing.add("SUPABASE_URL");
        if (isBlank(supabaseServiceKey)) missing.add("SUPABASE_SERVICE_KEY");
        if (isBlank(supabaseBucket)) missing.add("SUPABASE_BUCKET");

        boolean useSupabase = switch (mode) {
            case "local" -> false;
            case "supabase" -> {
                if (!missing.isEmpty()) {
                    // 값은 출력하지 않고, 빠진 환경 변수 이름만 알려준다
                    throw new IllegalStateException("storage.type=supabase 에 필요한 환경 변수가 없습니다: " + String.join(", ", missing));
                }
                yield true;
            }
            case "" -> missing.isEmpty();
            default -> throw new IllegalStateException("storage.type 은 local 또는 supabase 여야 합니다.");
        };

        if (useSupabase) {
            log.info("파일 저장소: Supabase Storage (bucket={})", supabaseBucket.trim());
            return new SupabaseStorageClient(supabaseUrl.trim(), supabaseServiceKey.trim(), supabaseBucket.trim());
        }
        log.info("파일 저장소: 로컬 디스크 ({})", uploadDir);
        return new LocalFileStorage(uploadDir);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
