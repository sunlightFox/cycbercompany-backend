#!/usr/bin/env bash
set -euo pipefail

APP_DIR=/opt/spring-agent-studio-backend
ENV_FILE=/etc/spring-agent-studio.env
PASSWORD_FILE="$APP_DIR/.postgres-password"
IMAGE=docker.m.daocloud.io/library/postgres:16-alpine

postgres_password=$(<"$PASSWORD_FILE")
rm -f "$PASSWORD_FILE"

sudo systemctl stop spring-agent-studio
docker rm -f agent-studio-postgres >/dev/null 2>&1 || true
docker volume create spring-agent-studio_postgres-data >/dev/null
docker run -d \
  --name agent-studio-postgres \
  --restart unless-stopped \
  -e POSTGRES_DB=agent_studio \
  -e POSTGRES_USER=agent_studio \
  -e POSTGRES_PASSWORD="$postgres_password" \
  -v spring-agent-studio_postgres-data:/var/lib/postgresql/data \
  --health-cmd='pg_isready -U agent_studio -d agent_studio' \
  --health-interval=5s \
  --health-timeout=5s \
  --health-retries=12 \
  -p 127.0.0.1:5432:5432 \
  "$IMAGE" >/dev/null

for _ in {1..12}; do
  state=$(docker inspect -f '{{.State.Health.Status}}' agent-studio-postgres)
  [[ "$state" == healthy ]] && break
  sleep 3
done
[[ "${state:-}" == healthy ]]

sudo cp "$APP_DIR/spring-agent-studio.jar" "$APP_DIR/spring-agent-studio.jar.pre-postgres"
sudo mv "$APP_DIR/spring-agent-studio.jar.new" "$APP_DIR/spring-agent-studio.jar"
sudo chown ubuntu:ubuntu "$APP_DIR/spring-agent-studio.jar"
sudo sed -i '/^SPRING_PROFILES_ACTIVE=/d;/^SPRING_DATASOURCE_/d;/^APP_PERSISTENCE_WATCHDOG_ENABLED=/d' "$ENV_FILE"
printf '\nSPRING_PROFILES_ACTIVE=postgres\nSPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/agent_studio\nSPRING_DATASOURCE_USERNAME=agent_studio\nSPRING_DATASOURCE_PASSWORD=%s\nAPP_PERSISTENCE_WATCHDOG_ENABLED=false\n' "$postgres_password" | sudo tee -a "$ENV_FILE" >/dev/null

sudo systemctl start spring-agent-studio
