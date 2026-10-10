#!/usr/bin/env bash
# Compiles a subset of the app in a private copy of the project, so several people / agents can work on different
# packages at the same time without breaking each other's builds.
#
#   tools/isolated-build.sh <name> <pkg> [<pkg> ...] [-- <gradle args>]
#
# <pkg> is a package path under com/app/shadetube (e.g. spotify, ui/home). core/, catalog/ and the root-level files
# (ShadeTubeApp.kt, AppGraph.kt, MainActivity.kt) are always included. Aliases:
#   backend  = every backend package + app/ (the AppGraph adapters)
#   uibase   = ui/theme ui/components ui/nav ui/debug ui/gallery
# UI screen packages (ui/home, ui/stats, ...) that are not listed are replaced by their stubs from tools/ui-stubs, so a
# screen can be compiled while the others are still being written.
# Default gradle args: :app:compileDebugKotlin. Example with tests:
#   tools/isolated-build.sh spotify spotify -- :app:testDebugUnitTest --tests 'com.app.shadetube.spotify.*'
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="$1"; shift
PKGS=()
BACKEND=(app widget youtube spotify musicbrainz lyrics links podcasts internetradio library stats scrobble downloads update localfiles playlistio playback)
UIBASE=(ui/theme ui/components ui/nav ui/debug ui/gallery)
while [[ $# -gt 0 && "$1" != "--" ]]; do
  case "$1" in
    backend) PKGS+=("${BACKEND[@]}") ;;
    uibase) PKGS+=("${UIBASE[@]}") ;;
    *) PKGS+=("$1") ;;
  esac
  shift
done
[[ $# -gt 0 ]] && shift
ARGS=("$@"); [[ ${#ARGS[@]} -eq 0 ]] && ARGS=(":app:compileDebugKotlin")

DEST="${ISOLATED_BUILD_DIR:-$ROOT/../.shadetube-builds}/$NAME"
mkdir -p "$DEST"
# Project skeleton (everything except sources and build outputs).
for f in build.gradle.kts settings.gradle.kts gradle.properties local.properties gradlew gradlew.bat; do cp "$ROOT/$f" "$DEST/"; done
rm -rf "$DEST/gradle" && cp -r "$ROOT/gradle" "$DEST/gradle"
mkdir -p "$DEST/app"
cp "$ROOT/app/build.gradle.kts" "$DEST/app/"
rm -rf "$DEST/app/src"
mkdir -p "$DEST/app/src/main/java/com/app/shadetube" "$DEST/app/src/test/java/com/app/shadetube"
cp -r "$ROOT/app/src/main/res" "$ROOT/app/src/main/AndroidManifest.xml" "$DEST/app/src/main/"
[[ -d "$ROOT/app/src/main/keepRules" ]] && cp -r "$ROOT/app/src/main/keepRules" "$DEST/app/src/main/"
[[ -d "$ROOT/app/src/main/assets" ]] && cp -r "$ROOT/app/src/main/assets" "$DEST/app/src/main/"
SRC="$ROOT/app/src/main/java/com/app/shadetube"
OUT="$DEST/app/src/main/java/com/app/shadetube"
find "$SRC" -maxdepth 1 -type f -name '*.kt' -exec cp {} "$OUT/" \;
for p in core catalog "${PKGS[@]}"; do
  if [[ -d "$SRC/$p" ]]; then mkdir -p "$OUT/$p"; cp -r "$SRC/$p/." "$OUT/$p/"; fi
  if [[ -d "$ROOT/app/src/test/java/com/app/shadetube/$p" ]]; then
    mkdir -p "$DEST/app/src/test/java/com/app/shadetube/$p"
    cp -r "$ROOT/app/src/test/java/com/app/shadetube/$p/." "$DEST/app/src/test/java/com/app/shadetube/$p/"
  fi
done
# Stubs for the UI screen packages that were not listed (and are not covered by a listed parent such as "ui").
for stub in "$ROOT"/tools/ui-stubs/*/; do
  name="$(basename "$stub")"
  listed=0
  for p in "${PKGS[@]}"; do [[ "$p" == "ui/$name" || "$p" == "ui" ]] && listed=1; done
  if [[ $listed -eq 0 ]]; then rm -rf "$OUT/ui/$name"; mkdir -p "$OUT/ui/$name"; cp "$stub"*.kt "$OUT/ui/$name/"; fi
done
cd "$DEST"
./gradlew --console=plain "${ARGS[@]}" 2>&1 | grep -vE '^(> Task|Calculating task graph|Configuration cache|Reusing configuration)' | tail -150
exit "${PIPESTATUS[0]}"
