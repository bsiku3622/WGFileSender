#!/bin/bash
# WGFileSender (macOS) build — compile with SwiftPM, then assemble a .app bundle.
set -euo pipefail
cd "$(dirname "$0")"

APP="WGFileSender.app"
CONFIG="${1:-release}"

echo "Building ($CONFIG)…"
swift build -c "$CONFIG"
BIN="$(swift build -c "$CONFIG" --show-bin-path)/WGFileSender"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/WGFileSender"
cp Info.plist "$APP/Contents/Info.plist"

# app icon: build .icns from the 1024 master
if [ -f icons/appicon-1024.png ]; then
  ICONSET="$(mktemp -d)/AppIcon.iconset"
  mkdir -p "$ICONSET"
  SRC=icons/appicon-1024.png
  for spec in "16 16x16" "32 16x16@2x" "32 32x32" "64 32x32@2x" \
              "128 128x128" "256 128x128@2x" "256 256x256" "512 256x256@2x" "512 512x512"; do
    set -- $spec
    sips -z "$1" "$1" "$SRC" --out "$ICONSET/icon_$2.png" >/dev/null
  done
  cp "$SRC" "$ICONSET/icon_512x512@2x.png"
  iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
  rm -rf "$(dirname "$ICONSET")"
fi

# ad-hoc signing for local runs
codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || true

echo "Built ./$APP"
