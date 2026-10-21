#!/usr/bin/env bash
#
# AirPlaySink build helper.
#
#   ./build.sh                 # same as ./build.sh debug
#   ./build.sh debug           # assemble debug APK
#   ./build.sh release         # assemble release APK (needs ANDROID_KEYSTORE_* env vars)
#   ./build.sh install         # build debug APK and install it on the connected device
#   ./build.sh test            # run JVM unit tests
#   ./build.sh clean           # gradle clean + wipe build logs
#   ./build.sh help
#
# Exit code is gradle's exit code (no pipe swallowing it), logs land in build/logs/.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

APP_ID="com.shilapi.airplaysink"
MAIN_ACTIVITY="$APP_ID/.MainActivity"
APK_DEBUG="app/build/outputs/apk/debug/app-debug.apk"
APK_RELEASE="app/build/outputs/apk/release/app-release.apk"
LOG_DIR="build/logs"

if [ -t 1 ]; then
    C_OK=$'\033[32m'; C_ERR=$'\033[31m'; C_WARN=$'\033[33m'; C_DIM=$'\033[2m'; C_END=$'\033[0m'
else
    C_OK=; C_ERR=; C_WARN=; C_DIM=; C_END=
fi

say()  { printf '%s\n' "$*"; }
head1() { printf '\n%s==> %s%s\n' "$C_DIM" "$*" "$C_END"; }
ok()   { printf '%s✓%s %s\n' "$C_OK" "$C_END" "$*"; }
warn() { printf '%s!%s %s\n' "$C_WARN" "$C_END" "$*"; }
die()  { printf '%s✗%s %s\n' "$C_ERR" "$C_END" "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
AirPlaySink build helper

  ./build.sh              assemble debug APK           (default)
  ./build.sh debug        assemble debug APK
  ./build.sh release      assemble release APK         (needs ANDROID_KEYSTORE_* env)
  ./build.sh install      build debug APK + adb install -r
  ./build.sh test         run JVM unit tests
  ./build.sh clean        gradle clean + drop build/logs
  ./build.sh help         this text

Environment:
  ANDROID_KEYSTORE_PATH / _PASSWORD / ANDROID_KEY_ALIAS / _KEY_PASSWORD   for release
  ANDROID_HOME            used only to bootstrap local.properties
EOF
}

# ---------------------------------------------------------------- environment

setup_java() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        :
    else
        local home
        home="$(/usr/libexec/java_home -v 17 2>/dev/null)"
        [ -n "$home" ] || die "JDK 17 not found. Install it or export JAVA_HOME."
        export JAVA_HOME="$home"
    fi

    local major
    major="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    [ -n "$major" ] || die "Cannot read java version from $JAVA_HOME."
    if [ "$major" -lt 17 ]; then
        die "JDK 17+ required, found $major ($JAVA_HOME)."
    fi
    say "    java  $major  $JAVA_HOME"
}

setup_sdk() {
    if ! grep -q '^sdk\.dir=' local.properties 2>/dev/null; then
        if [ -n "${ANDROID_HOME:-}" ]; then
            printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
            warn "local.properties created from ANDROID_HOME=$ANDROID_HOME"
        else
            die "No sdk.dir in local.properties and ANDROID_HOME is unset."
        fi
    fi
    local sdk
    sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
    [ -d "$sdk" ] || die "sdk.dir points to a missing directory: $sdk"

    # compileSdk 37 needs platforms/android-37.0, and targetSdk 37 wants build-tools 37.
    if [ ! -d "$sdk/platforms/android-37.0" ]; then
        warn "platforms/android-37.0 missing — installing via sdkmanager"
        "$sdk/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" >/dev/null \
            || die "sdkmanager failed for platforms;android-37.0"
    fi
    say "    sdk   $sdk"
}

# Run gradle with the exit code preserved, tee'd into build/logs/<label>.log.
run_gradle() {
    local label="$1"; shift
    mkdir -p "$LOG_DIR"
    local log="$LOG_DIR/$label.log"
    local start rc

    start="$(date +%s)"
    say "    task  ./gradlew $*"
    say ""
    set -o pipefail
    ./gradlew "$@" 2>&1 | tee "$log"
    rc=${PIPESTATUS[0]}
    set +o pipefail

    if [ "$rc" -ne 0 ]; then
        say ""
        warn "gradle exited $rc — first errors from $log:"
        grep -nE '^(e: |FAILURE:|\* What went wrong|> Task .*FAILED)' "$log" | head -20
        die "build failed"
    fi
    ok "done in $(( $(date +%s) - start ))s  (log: $log)"
}

apk_size() {
    [ -f "$1" ] || return 0
    local mb
    mb="$(du -m "$1" | cut -f1)"
    ok "apk   $1  (${mb} MB)"
}

# -------------------------------------------------------------------- targets

cmd_debug() {
    head1 "assembleDebug"
    setup_java; setup_sdk
    run_gradle debug :app:assembleDebug
    apk_size "$APK_DEBUG"
}

cmd_release() {
    head1 "assembleRelease"
    setup_java; setup_sdk
    local missing=""
    local v val
    for v in ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
        eval "val=\${$v:-}"
        [ -n "$val" ] || missing="$missing $v"
    done
    if [ -n "$missing" ]; then
        warn "unsigned build — missing:$missing"
        warn "the resulting APK cannot be installed over a signed one"
    else
        [ -f "$ANDROID_KEYSTORE_PATH" ] || die "keystore not found: $ANDROID_KEYSTORE_PATH"
        say "    sign  $ANDROID_KEYSTORE_PATH  alias=$ANDROID_KEY_ALIAS"
    fi
    run_gradle release :app:assembleRelease
    # Unsigned when no keystore: AGP then emits app-release-unsigned.apk.
    local apk="$APK_RELEASE"
    [ -f "$apk" ] || apk="app/build/outputs/apk/release/app-release-unsigned.apk"
    apk_size "$apk"
}

cmd_install() {
    command -v adb >/dev/null 2>&1 || die "adb not on PATH (add \$ANDROID_HOME/platform-tools)."
    cmd_debug

    head1 "install"
    local devices
    devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
    [ -n "$devices" ] || die "no device in 'device' state — check 'adb devices'."
    if [ "$(printf '%s\n' "$devices" | wc -l)" -gt 1 ]; then
        warn "multiple devices, using the first one: $(printf '%s\n' "$devices" | head -1)"
    fi

    adb install -r "$APK_DEBUG" || die "adb install failed"
    ok "installed $APP_ID"
    say "    start with: adb shell am start -n $MAIN_ACTIVITY"
}

cmd_test() {
    head1 "testDebugUnitTest"
    setup_java; setup_sdk
    run_gradle test :app:testDebugUnitTest
    say ""
    say "    report: app/build/reports/tests/testDebugUnitTest/index.html"
}

cmd_clean() {
    head1 "clean"
    setup_java; setup_sdk
    # Let gradle delete app/build: a bare `rm -rf` over ~1500 files trips the
    # bulk-delete guard on this machine and silently does nothing.
    run_gradle clean :app:clean
    rm -rf build/logs build
    if [ -d app/build ]; then
        die "app/build survived clean — delete it manually"
    fi
    ok "removed app/build and build/"
}

# ----------------------------------------------------------------------- main

case "${1:-debug}" in
    debug|d)     cmd_debug ;;
    release|r)   cmd_release ;;
    install|i)   cmd_install ;;
    test|t)      cmd_test ;;
    clean|c)     cmd_clean ;;
    help|-h|--help) usage ;;
    *)           warn "unknown target: $1"; say ""; usage; exit 2 ;;
esac
