#!/usr/bin/env bash
set -euo pipefail

APP_DIR=/opt/spring-agent-studio-backend
ENV_FILE=/etc/spring-agent-studio.env

sudo systemctl stop spring-agent-studio || true
sudo cp "$APP_DIR/spring-agent-studio.jar.pre-postgres" "$APP_DIR/spring-agent-studio.jar"
sudo chown ubuntu:ubuntu "$APP_DIR/spring-agent-studio.jar"
sudo sed -i '/^SPRING_PROFILES_ACTIVE=/d;/^SPRING_DATASOURCE_/d;/^APP_PERSISTENCE_WATCHDOG_ENABLED=/d' "$ENV_FILE"
printf '\nAPP_PERSISTENCE_WATCHDOG_ENABLED=false\n' | sudo tee -a "$ENV_FILE" >/dev/null
sudo systemctl start spring-agent-studio
