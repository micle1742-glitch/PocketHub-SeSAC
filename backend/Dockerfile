# PocketHub 백엔드 배포 이미지 (backend/ 폴더에서 빌드: docker build -t pockethub-backend .)
# 실행할 때 넘길 환경 변수: DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET (필수)
#                          PORT, CORS_ALLOWED_ORIGINS (선택)

# ---- 1단계: 빌드 (JDK 21 + Gradle Wrapper) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 캐시를 위해 빌드 설정을 먼저 복사
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# Windows 에서 체크아웃하면 gradlew 가 CRLF 일 수 있어 LF 로 바꾸고 실행 권한을 준다
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
    && ./gradlew dependencies --no-daemon > /dev/null

COPY src ./src
RUN ./gradlew bootJar --no-daemon

# ---- 2단계: 실행 (JRE 21) ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# root 가 아닌 사용자로 실행. 업로드 파일은 /app/uploads 에 저장된다 (upload.dir 기본값 uploads)
RUN groupadd --system app && useradd --system --gid app app \
    && mkdir -p /app/uploads && chown -R app:app /app
COPY --from=build --chown=app:app /workspace/build/libs/*.jar app.jar
USER app

# 서버 포트는 PORT 환경 변수 (application.yml: server.port=${PORT:8080})
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
