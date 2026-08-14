#!/usr/bin/env bash
set -euo pipefail

# systemd runs this independently of the JVM. A process can remain alive while
# its HTTP connector or persistence layer is no longer usable, so restart it
# only after consecutive failed readiness probes.
unit="${1:-spring-agent-studio}"
url="${APP_HEALTHCHECK_URL:-https://127.0.0.1:8080/actuator/health/readiness}"
state_dir="${APP_HEALTHCHECK_STATE_DIR:-/run/spring-agent-studio}"
failure_file="$state_dir/consecutive-healthcheck-failures"
threshold="${APP_HEALTHCHECK_FAILURE_THRESHOLD:-3}"

mkdir -p "$state_dir"

if curl --fail --silent --show-error --max-time 8 --insecure "$url" >/dev/null; then
  rm -f "$failure_file"
  exit 0
fi

failures=0
if [[ -f "$failure_file" ]]; then
  failures=$(<"$failure_file")
fi
failures=$((failures + 1))
printf '%s\n' "$failures" >"$failure_file"

if (( failures >= threshold )); then
  rm -f "$failure_file"
  systemctl restart "$unit"
fi
