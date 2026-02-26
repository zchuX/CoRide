#!/usr/bin/env bash
set -euo pipefail

# Thin wrapper to scripts/easy-api.sh for convenience
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$ROOT_DIR/scripts/easy-api.sh" "$@"