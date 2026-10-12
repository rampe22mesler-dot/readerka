#!/usr/bin/env bash
# Windows/Git Bash entry point for the native release builder.
set -euo pipefail
cd "$(dirname "$0")/.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Build-Release.ps1 "$@"
