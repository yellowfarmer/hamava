#!/usr/bin/env bash
# Small bootstrap wrapper used because the source bundle is generated without the official wrapper JAR.
set -euo pipefail
VERSION="8.9"
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/hamava-bootstrap"
DIST="$BASE/gradle-$VERSION"
if [[ ! -x "$DIST/bin/gradle" ]]; then
  mkdir -p "$BASE"
  ZIP="$BASE/gradle-$VERSION-bin.zip"
  echo "Downloading Gradle $VERSION (first build only)..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  else
    echo "Install curl or wget to download Gradle." >&2; exit 1
  fi
  unzip -q -o "$ZIP" -d "$BASE"
  rm -f "$ZIP"
fi
exec "$DIST/bin/gradle" "$@"
