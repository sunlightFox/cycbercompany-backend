#!/usr/bin/env bash
set -euo pipefail

APP_DIR=/opt/cycbercompany-backend
ENV_FILE=/etc/cycbercompany.env
PASSWORD_FILE="$APP_DIR/.postgres-password"
IMAGE=docker.m.daocloud.io/library/postgres:16-alpine

postgres_password=$(<"$PASSWORD_FILE")
rm -f "$PASSWORD_FILE"

sudo systemctl stop cycbercompany
docker rm -f cycbercompany-postgres >/dev/null 2>&1 || true
docker volume create cycbercompany_postgres-data >/dev/null
docker run -d \
  --name cycbercompany-postgres \
  --restart unless-stopped \
  -e POSTGRES_DB=cycbercompany \
  -e POSTGRES_USER=cycbercompany \
  -e POSTGRES_PASSWORD="$postgres_password" \
  -v cycbercompany_postgres-data:/var/lib/postgresql/data \
  --health-cmd='pg_isready -U cycbercompany -d cycbercompany' \
  --health-interval=5s \
  --health-timeout=5s \
  --health-retries=12 \
  -p 127.0.0.1:5432:5432 \
  "$IMAGE" >/dev/null

for _ in {1..12}; do
  state=$(docker inspect -f '{{.State.Health.Status}}' cycbercompany-postgres)
  [[ "$state" == healthy ]] && break
  sleep 3
done
[[ "${state:-}" == healthy ]]

sudo cp "$APP_DIR/cycbercompany.jar" "$APP_DIR/cycbercompany.jar.pre-postgres"
sudo mv "$APP_DIR/cycbercompany.jar.new" "$APP_DIR/cycbercompany.jar"
sudo chown ubuntu:ubuntu "$APP_DIR/cycbercompany.jar"
sudo sed -i '/^SPRING_PROFILES_ACTIVE=/d;/^SPRING_DATASOURCE_/d;/^APP_PERSISTENCE_WATCHDOG_ENABLED=/d' "$ENV_FILE"
printf '\nSPRING_PROFILES_ACTIVE=postgres\nSPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/cycbercompany\nSPRING_DATASOURCE_USERNAME=cycbercompany\nSPRING_DATASOURCE_PASSWORD=%s\nAPP_PERSISTENCE_WATCHDOG_ENABLED=false\n' "$postgres_password" | sudo tee -a "$ENV_FILE" >/dev/null

sudo systemctl start cycbercompany
