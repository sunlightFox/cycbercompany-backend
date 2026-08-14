#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="${1:-$HOME/cycbercompany/website/build}"
TARGET_DIR="${2:-/var/www/cycbercompany-docs}"
RELEASE_DIR="$TARGET_DIR/releases/$(date +%Y%m%d%H%M%S)"

test -f "$SOURCE_DIR/index.html"
mkdir -p "$TARGET_DIR/releases"
mkdir -p "$RELEASE_DIR"
rsync -a --delete "$SOURCE_DIR/" "$RELEASE_DIR/"
ln -sfn "$RELEASE_DIR" "$TARGET_DIR/current"
nginx -t
systemctl reload nginx
find "$TARGET_DIR/releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' | sort -nr | tail -n +6 | cut -d' ' -f2- | xargs -r rm -rf
