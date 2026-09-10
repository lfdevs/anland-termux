#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/out"
VERSION_NAME="$(awk -F'"' '/^[[:space:]]*versionName[[:space:]]*=/ { print $2; exit }' "$ROOT_DIR/app/build.gradle")"
APK_NAME="AnlandTermux-${VERSION_NAME}-compatible.apk"

if [[ -z "$VERSION_NAME" ]]; then
  echo "Could not read versionName from app/build.gradle" >&2
  exit 1
fi

if ! command -v gradle >/dev/null 2>&1; then
  echo "gradle must be available on PATH" >&2
  exit 1
fi

GRADLE_VERSION="$(gradle --version | awk '/^Gradle / { print $2; exit }')"
if [[ "$GRADLE_VERSION" != "9.7.1" ]]; then
  echo "Gradle 9.7.1 is required, found: ${GRADLE_VERSION:-unknown}" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

(cd "$ROOT_DIR/app" && gradle --no-daemon assembleCompatibleDebug)

mapfile -t DEBUG_APKS < <(find "$ROOT_DIR/app/build/outputs/apk/compatible/debug" -maxdepth 1 -type f -name "*.apk" | sort)
if [[ "${#DEBUG_APKS[@]}" -ne 1 ]]; then
  echo "Expected exactly one compatible debug APK, found ${#DEBUG_APKS[@]}:" >&2
  printf '  %s\n' "${DEBUG_APKS[@]}" >&2
  exit 1
fi

cp "${DEBUG_APKS[0]}" "$OUT_DIR/$APK_NAME"

echo "Built $OUT_DIR/$APK_NAME"
