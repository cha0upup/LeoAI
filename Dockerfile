ARG RUNTIME_IMAGE=eclipse-temurin:17-jre

FROM ${RUNTIME_IMAGE}

ARG JAR_URL=https://github.com/cha0upup/LeoAI/releases/download/v0.0.7/LeoAi-0.0.7-SNAPSHOT.jar

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="" \
    SPRING_DATASOURCE_URL=jdbc:sqlite:/app/data/data.db \
    VFSPATH=/app/data/root

WORKDIR /app/data

COPY root /app/root-seed
COPY docker/entrypoint.sh /app/entrypoint.sh

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl; \
    curl -fsSL "$JAR_URL" -o /app/LeoAi.jar; \
    apt-get purge -y --auto-remove curl; \
    rm -rf /var/lib/apt/lists/*; \
    chmod +x /app/entrypoint.sh; \
    mkdir -p /app/data; \
    groupadd --system leoai; \
    useradd --system --gid leoai --home-dir /app/data leoai; \
    chown -R leoai:leoai /app

USER leoai

EXPOSE 8082
VOLUME ["/app/data"]

ENTRYPOINT ["/app/entrypoint.sh"]
