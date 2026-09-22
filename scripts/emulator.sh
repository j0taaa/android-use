#!/usr/bin/env bash
set -euo pipefail
sdk_dir="${ANDROID_HOME:-$HOME/Android/Sdk}"
avd_name="${1:-AndroidUse_API35}"
if [ "$#" -gt 0 ]; then shift; fi
exec "$sdk_dir/emulator/emulator" -avd "$avd_name" -gpu swiftshader_indirect -memory 2048 -cores 2 "$@"
