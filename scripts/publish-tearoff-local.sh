#!/usr/bin/env bash
# Publishes this fork to Maven Local for an app pinned to one Nucleus version.
#
# decorated-window-tao is built from source under $TARGET, with the native
# libraries of the published $BASE jar (no Rust or native source changed on
# this branch, so they are the ones its JNI declarations were built against);
# every other Nucleus artifact the app needs is copied from $BASE under $TARGET
# (scripts/republish-local.py), so the whole set resolves to one version.
#
#   scripts/publish-tearoff-local.sh [target-version] [extra artifact ids...]
set -euo pipefail

BASE="3.0.0-dev-202610272317"
TARGET="${1:-3.0.0-dev-202610272317-1-tearoff}"
shift || true
REPO="${NUCLEUS_SOURCE_REPO:-https://repo1.maven.org/maven2}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="decorated-window-tao"

# What Pardes desktop takes from Nucleus (gradle/libs.versions.toml). The Gradle plugin is unchanged and stays on
# $BASE, from the plugin portal: an app pins it apart.
ARTIFACTS=(
  nucleus.core-runtime nucleus.aot-runtime nucleus.darkmode-detector nucleus.nucleus-application
  nucleus.decorated-window-core nucleus.decorated-window-tao nucleus.decorated-window-jewel
  nucleus.graalvm-runtime nucleus.updater-runtime nucleus.native-ssl nucleus.native-http
  nucleus.energy-manager nucleus.system-color nucleus.native-http-ktor nucleus.launcher-macos
  nucleus.launcher-windows nucleus.launcher-linux nucleus.menu-macos nucleus.sf-symbols nucleus.share
  nucleus.notification-common nucleus.system-info nucleus.taskbar-progress-tao
  "$@"
)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
jar="$work/$MODULE.jar"
curl -sSfL -o "$jar" "$REPO/dev/nucleusframework/nucleus.$MODULE/$BASE/nucleus.$MODULE-$BASE.jar"
mkdir -p "$ROOT/$MODULE/src/main/resources"
unzip -q -o "$jar" 'nucleus/native/*' -d "$ROOT/$MODULE/src/main/resources"

# CI=true: the native tasks skip libraries already in the resources. GITHUB_REF: the publish version.
(cd "$ROOT" && CI=true GITHUB_REF="refs/tags/v$TARGET" ./gradlew ":$MODULE:publishToMavenLocal" --no-configuration-cache ${GRADLE_ARGS:-})

python3 "$ROOT/scripts/republish-local.py" --from "$BASE" --to "$TARGET" --repo "$REPO" \
  --skip "nucleus.$MODULE" -- "${ARTIFACTS[@]}"
