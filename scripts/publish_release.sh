#!/usr/bin/env bash
# Bumpea versionCode, compila el release firmado y lo publica junto a version.json en
# https://magis-launcher.surge.sh (lo consume UpdateChecker). Requiere:
#   - .env en la raíz con KEYSTORE_* (firma, ver app/build.gradle) y SURGE_EMAIL/SURGE_PASSWORD.
#   - `surge` instalado, o `npx` (node vía mise) para correrlo con `npx --yes surge`.
#
# Uso: scripts/publish_release.sh [--dry-run] "changelog de esta versión"
#   --dry-run  bumpea, compila y arma dist/ pero no sube a surge.
set -euo pipefail

DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
  shift
fi

if [ $# -lt 1 ] || [ -z "$1" ]; then
  echo "Uso: $0 [--dry-run] \"changelog de esta versión\"" >&2
  exit 1
fi

CHANGELOG="$1"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_FILE="$ROOT_DIR/app/build.gradle"
DOMAIN="magis-launcher.surge.sh"

CURRENT_VERSION_CODE=$(grep -oE 'versionCode [0-9]+' "$BUILD_FILE" | grep -oE '[0-9]+')
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))

echo "Versión $CURRENT_VERSION_CODE -> $NEW_VERSION_CODE"
sed -i -E "s/versionCode [0-9]+/versionCode $NEW_VERSION_CODE/" "$BUILD_FILE"

echo "Compilando release firmado..."
(cd "$ROOT_DIR" && ./gradlew assembleRelease -q)

DIST_DIR="$ROOT_DIR/dist"
mkdir -p "$DIST_DIR"
# Solo se publica la última APK: UpdateChecker descarga únicamente la que indica version.json y
# solo ofrece versiones mayores a la instalada, así que las viejas no sirven.
rm -f "$DIST_DIR"/magis-launcher-release-*.apk
APK_NAME="magis-launcher-release-$NEW_VERSION_CODE.apk"
cp "$ROOT_DIR/app/build/outputs/apk/release/$APK_NAME" "$DIST_DIR/$APK_NAME"

APK_URL="https://$DOMAIN/$APK_NAME"

CHANGELOG="$CHANGELOG" NEW_VERSION_CODE="$NEW_VERSION_CODE" APK_URL="$APK_URL" DIST_DIR="$DIST_DIR" python3 -c "
import json
import os

data = {
    'versionCode': int(os.environ['NEW_VERSION_CODE']),
    'versionName': os.environ['NEW_VERSION_CODE'],
    'apkUrl': os.environ['APK_URL'],
    'changelog': os.environ['CHANGELOG'],
}
with open(os.path.join(os.environ['DIST_DIR'], 'version.json'), 'w') as f:
    json.dump(data, f, ensure_ascii=False)
"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "Dry run: no se sube a surge. Contenido de $DIST_DIR:"
  ls -la "$DIST_DIR"
  cat "$DIST_DIR/version.json"; echo
  exit 0
fi

echo "Publicando en https://$DOMAIN ..."
# shellcheck disable=SC1091
. "$ROOT_DIR/.env"
SURGE_CMD="surge"
command -v surge >/dev/null || SURGE_CMD="npx --yes surge"
script -q -c "$SURGE_CMD '$DIST_DIR' $DOMAIN" /dev/null <<< "$SURGE_EMAIL"$'\n'"$SURGE_PASSWORD"

echo "Listo: $APK_URL"
