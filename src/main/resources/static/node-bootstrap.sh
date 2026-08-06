#!/usr/bin/env sh
set -eu

server=""
token=""
workspace="$(pwd)"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --server) server="$2"; shift 2 ;;
    --token) token="$2"; shift 2 ;;
    --workspace) workspace="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$server" ] && [ -n "$token" ] || { echo "usage: sh node-bootstrap.sh --server URL --token TOKEN [--workspace PATH]" >&2; exit 2; }

if [ -x "./gradlew" ]; then gradle="./gradlew"; else echo "Run this script from the Java backend project root." >&2; exit 1; fi
args="register --server $server --token $token --workspace $workspace"
"$gradle" --no-daemon :agent-studio-node-java:run "--args=$args"
"$gradle" --no-daemon :agent-studio-node-java:run "--args=start"
