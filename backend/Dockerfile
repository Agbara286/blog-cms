# Multi-stage so `docker compose up` works from a fresh clone with no local JDK.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Dependencies first: this layer is reused until the build files change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon --console=plain > /dev/null

COPY src src
RUN ./gradlew bootJar --no-daemon --console=plain -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S blogcms && adduser -S blogcms -G blogcms

COPY --from=builder /workspace/build/libs/*.jar app.jar

USER blogcms

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
