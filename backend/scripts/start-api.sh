#!/usr/bin/env bash
###############################################################################
#  Calypso – start-api.sh
#
#  Quick-launch for the Spring-Boot API in local dev.
#
#  SETUP
#  -----
#  1) Make the script executable *once* (from project root):
#       chmod +x backend/scripts/start-api.sh
#
#  2) Add this alias to ~/.zshrc or ~/.bashrc, swapping in your real key:
#       alias api='export OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxx && \
#                  sh ~/calypso-backend/backend/scripts/start-api.sh dev'
#
#     Now you can start the backend with a single word:
#       api
#
###############################################################################

set -euo pipefail
PROFILE=${1:-dev}

# ── Verify OpenAI key ────────────────────────────────────────────────────────
if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "❌  OPENAI_API_KEY is not set."
  echo "   Export it in this shell or inline in the alias."
  exit 1
fi

# ── Rama temp-dir housekeeping ───────────────────────────────────────────────
RAMA_TEMP="/tmp/rama_temp"
mkdir -p "$RAMA_TEMP"
find "$RAMA_TEMP" -type f -mtime +1 -delete
rm -rf "$RAMA_TEMP"/ipc* 2>/dev/null || true

# ── Build + run Spring Boot ─────────────────────────────────────────────────
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &>/dev/null && pwd )"
API_DIR="$SCRIPT_DIR/../../api"
cd "$API_DIR"

echo "▶️  Building Calypso API..."
mvn -q clean install

echo "🚀  Starting Calypso API (profile=$PROFILE)…"
SPRING_PROFILES_ACTIVE="$PROFILE" \
  mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments=" \
       -Xss4m -Xmx12g -XX:+UseG1GC \
       -XX:ReservedCodeCacheSize=256m \
       -XX:HeapDumpPath=$RAMA_TEMP \
       -Djava.io.tmpdir=$RAMA_TEMP \
       -XX:+HeapDumpOnOutOfMemoryError"

