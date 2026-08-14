#!/usr/bin/env sh
set -eu

server=""
workspace="$(pwd)"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --server) server="$2"; shift 2 ;;
    --workspace) workspace="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$server" ] || { echo "usage: sh node-bootstrap.sh --server URL [--workspace PATH]" >&2; exit 2; }

if [ -x "./gradlew" ]; then gradle="./gradlew"; else echo "Run this script from the Java backend project root." >&2; exit 1; fi
args="register --server $server --workspace $workspace"
"$gradle" --no-daemon :cycbercompany-node-java:run "--args=$args"
"$gradle" --no-daemon :cycbercompany-node-java:run "--args=start"
