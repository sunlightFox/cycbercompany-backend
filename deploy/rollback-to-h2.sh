#!/usr/bin/env bash
set -euo pipefail

APP_DIR=/opt/cycbercompany-backend
ENV_FILE=/etc/cycbercompany.env

sudo systemctl stop cycbercompany || true
sudo cp "$APP_DIR/cycbercompany.jar.pre-postgres" "$APP_DIR/cycbercompany.jar"
sudo chown ubuntu:ubuntu "$APP_DIR/cycbercompany.jar"
sudo sed -i '/^SPRING_PROFILES_ACTIVE=/d;/^SPRING_DATASOURCE_/d;/^APP_PERSISTENCE_WATCHDOG_ENABLED=/d' "$ENV_FILE"
printf '\nAPP_PERSISTENCE_WATCHDOG_ENABLED=false\n' | sudo tee -a "$ENV_FILE" >/dev/null
sudo systemctl start cycbercompany
