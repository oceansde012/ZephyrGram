#!/usr/bin/env bash
# Build a Telegram-Android-based project on Linux.
#
# Usage: build-android.sh <project_dir> [assembleDebug|assembleRelease]
#
# Required env: TG_API_ID, TG_API_HASH (values are never printed)
# Required env: ANDROID_HOME or ANDROID_SDK_ROOT
# Exit codes: 1 = usage/input error, 2 = missing Telegram credentials,
#             3 = Android SDK missing and cannot be installed automatically
set -euo pipefail

usage() {
  echo "Usage: $(basename "$0") <project_dir> [assembleDebug|assembleRelease]" >&2
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 1
fi

PROJECT_DIR="$1"
TASK="${2:-assembleDebug}"

case "$TASK" in
  assembleDebug|assembleRelease) ;;
  *)
    echo "error: unsupported task '$TASK' (use assembleDebug or assembleRelease)" >&2
    exit 1
    ;;
esac

if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "error: project directory not found: $PROJECT_DIR" >&2
  exit 1
fi
PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"

# --- Telegram API credentials: report presence only, never values ---
if [[ -z "${TG_API_ID:-}" || -z "${TG_API_HASH:-}" ]]; then
  echo "error: TG_API_ID and TG_API_HASH must both be set and non-empty." >&2
  echo "  TG_API_ID set:   $([[ -n "${TG_API_ID:-}" ]] && echo yes || echo no)" >&2
  echo "  TG_API_HASH set: $([[ -n "${TG_API_HASH:-}" ]] && echo yes || echo no)" >&2
  echo "Get your own api_id / api_hash at https://my.telegram.org" >&2
  exit 2
fi
echo "TG_API_ID: set"
echo "TG_API_HASH: set (length ${#TG_API_HASH})"

# --- Android SDK ---
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  echo "error: ANDROID_HOME (or ANDROID_SDK_ROOT) is not set." >&2
  echo "Point it at an Android SDK directory, e.g. export ANDROID_HOME=\$HOME/Android/Sdk" >&2
  exit 3
fi

missing=()
[[ -d "$SDK_ROOT/platforms/android-36" ]] || missing+=("platforms;android-36")
[[ -d "$SDK_ROOT/build-tools/36.0.0" ]]   || missing+=("build-tools;36.0.0")
[[ -d "$SDK_ROOT/ndk/27.2.12479018" ]]   || missing+=("ndk;27.2.12479018")
[[ -d "$SDK_ROOT/cmake/3.22.1" ]]        || missing+=("cmake;3.22.1")

if (( ${#missing[@]} > 0 )); then
  echo "Missing SDK packages in $SDK_ROOT: ${missing[*]}"

  SDKMANAGER=""
  if command -v sdkmanager >/dev/null 2>&1; then
    SDKMANAGER="$(command -v sdkmanager)"
  elif [[ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
    SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  fi

  if [[ -z "$SDKMANAGER" ]]; then
    cat >&2 <<EOF
error: sdkmanager not found (not on PATH and not at $SDK_ROOT/cmdline-tools/latest/bin/sdkmanager).

Install the Android command-line tools manually:
  1. Download commandlinetools-linux-<version>_latest.zip from
     https://dl.google.com/android/repository/   (current name: see developer.android.com/studio)
  2. mkdir -p "$SDK_ROOT/cmdline-tools"
     unzip -q commandlinetools-linux-<version>_latest.zip -d "$SDK_ROOT/cmdline-tools"
     mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  3. Re-run this script.
Note: dl.google.com may be blocked by the sandbox network policy.
EOF
    exit 3
  fi

  echo "Installing SDK packages with: $SDKMANAGER"
  # 'yes' gets SIGPIPE when sdkmanager exits; the || true keeps pipefail from failing the step.
  { yes || true; } | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses
  "$SDKMANAGER" --sdk_root="$SDK_ROOT" \
    "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018" "cmake;3.22.1" "platform-tools"
fi

# --- local.properties: set only the sdk.dir line, keep everything else ---
LP="$PROJECT_DIR/local.properties"
touch "$LP"
{ grep -v '^sdk\.dir=' "$LP" || true; printf 'sdk.dir=%s\n' "$SDK_ROOT"; } > "$LP.tmp"
mv "$LP.tmp" "$LP"

# --- Gradle ---
cd "$PROJECT_DIR"
if [[ -x ./gradlew ]]; then
  GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  echo "error: neither ./gradlew nor 'gradle' found" >&2
  exit 1
fi

echo "Running: ${GRADLE[*]} --no-daemon $TASK (in $PROJECT_DIR)"
"${GRADLE[@]}" --no-daemon -Dorg.gradle.jvmargs=-Xmx4g "$TASK" \
  -PTG_API_ID="$TG_API_ID" -PTG_API_HASH="$TG_API_HASH"

# --- Report produced APKs ---
echo
echo "APKs produced:"
apk_count=0
while IFS= read -r apk; do
  apk_count=$((apk_count + 1))
  printf '  %8s  %s\n' "$(du -h "$apk" | cut -f1)" "$apk"
done < <(find "$PROJECT_DIR" -name '*.apk' -newer "$LP" -type f)

if (( apk_count == 0 )); then
  echo "  (none found newer than local.properties)"
fi
