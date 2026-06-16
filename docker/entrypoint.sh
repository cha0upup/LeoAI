#!/usr/bin/env sh
set -eu

DATA_DIR=/app/data
VFS_DIR="${VFSPATH:-/app/data/root}"
DB_URL="${SPRING_DATASOURCE_URL:-jdbc:sqlite:/app/data/data.db}"

mkdir -p "$DATA_DIR"

if [ ! -d "$VFS_DIR" ]; then
  mkdir -p "$(dirname "$VFS_DIR")"
  cp -R /app/root-seed "$VFS_DIR"
fi

exec java ${JAVA_OPTS:-} \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -jar /app/LeoAi.jar \
  --spring.datasource.url="$DB_URL" \
  --VfsPath="$VFS_DIR" \
  "$@"
