#!/usr/bin/env bash

set -euo pipefail

BACKEND_URL="https://cloud.jefflumber341.workers.dev/"
BUILD_MODE="release"
PROJECT_TYPE="auto"
PACKAGE_NAME=""
APP_NAME=""
OUT_DIR="./cloud-build-output"
POLL_SECONDS=8
MAX_POLLS=300

usage() {
  cat <<'EOF'
Cloud Build Terminal Client

Usage:
  bash termux-cloud-build.sh <source.zip> [options]

Options:
  --app-name NAME       App display name. Default: ZIP file name without .zip
  --package-name NAME   Android package name override
  --mode MODE           release or debug. Default: release
  --project-type TYPE   auto, flutter, react_native, expo, native_android, capacitor, cordova, ionic
  --backend URL         Worker backend URL. Default: https://cloud.jefflumber341.workers.dev/
  --out DIR             Output directory. Default: ./cloud-build-output
  --poll SECONDS        Poll interval. Default: 8
  --help                Show this help

Example:
  bash termux-cloud-build.sh app.zip --app-name "My App" --mode release
EOF
}

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

info() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1"
}

need_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

find_python() {
  if command -v python3 >/dev/null 2>&1; then
    printf 'python3'
  elif command -v python >/dev/null 2>&1; then
    printf 'python'
  else
    fail "Missing required command: python3 or python"
  fi
}

json_get() {
  local key="$1"
  "$PYTHON" -c 'import json,sys
key=sys.argv[1]
try:
    data=json.load(sys.stdin)
except Exception:
    sys.exit(1)
value=data
for part in key.split("."):
    value=value.get(part) if isinstance(value, dict) else None
if value is None:
    print("")
elif isinstance(value, bool):
    print("true" if value else "false")
else:
    print(value)
' "$key"
}

json_escape() {
  "$PYTHON" -c 'import json,sys; print(json.dumps(sys.stdin.read()))'
}

api_error() {
  local body="$1"
  local fallback="$2"
  local parsed
  parsed=$(printf '%s' "$body" | json_get error 2>/dev/null || true)
  if [ -n "$parsed" ]; then
    printf '%s' "$parsed"
  else
    printf '%s' "$fallback"
  fi
}

curl_json() {
  local method="$1"
  local url="$2"
  local body="${3:-}"
  local response status
  response="$TMP_DIR/response.json"
  if [ -n "$body" ]; then
    status=$(curl -sS -X "$method" -H 'Content-Type: application/json' -d "$body" -o "$response" -w '%{http_code}' "$url")
  else
    status=$(curl -sS -X "$method" -o "$response" -w '%{http_code}' "$url")
  fi
  if [ "$status" -lt 200 ] || [ "$status" -ge 300 ]; then
    fail "$(api_error "$(cat "$response")" "HTTP $status calling $url")"
  fi
  cat "$response"
}

curl_download() {
  local url="$1"
  local output="$2"
  local response status
  response="$TMP_DIR/download-error.txt"
  status=$(curl -L -sS -o "$output" -w '%{http_code}' "$url" || true)
  if [ "$status" -lt 200 ] || [ "$status" -ge 300 ]; then
    if [ -f "$output" ]; then cp "$output" "$response" 2>/dev/null || true; fi
    fail "Download failed with HTTP $status: $url"
  fi
}

normalize_backend() {
  local url="$1"
  url="${url%/}"
  printf '%s' "$url"
}

SOURCE_ZIP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --app-name)
      APP_NAME="${2:-}"
      shift 2
      ;;
    --package-name)
      PACKAGE_NAME="${2:-}"
      shift 2
      ;;
    --mode)
      BUILD_MODE="${2:-}"
      shift 2
      ;;
    --project-type)
      PROJECT_TYPE="${2:-}"
      shift 2
      ;;
    --backend)
      BACKEND_URL="${2:-}"
      shift 2
      ;;
    --out)
      OUT_DIR="${2:-}"
      shift 2
      ;;
    --poll)
      POLL_SECONDS="${2:-}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --*)
      fail "Unknown option: $1"
      ;;
    *)
      if [ -n "$SOURCE_ZIP" ]; then
        fail "Only one ZIP file can be provided"
      fi
      SOURCE_ZIP="$1"
      shift
      ;;
  esac
done

[ -n "$SOURCE_ZIP" ] || { usage; exit 1; }
[ -f "$SOURCE_ZIP" ] || fail "ZIP file not found: $SOURCE_ZIP"
case "$BUILD_MODE" in release|debug) ;; *) fail "--mode must be release or debug" ;; esac
case "$PROJECT_TYPE" in auto|flutter|react_native|expo|native_android|capacitor|cordova|ionic) ;; *) fail "Unsupported project type: $PROJECT_TYPE" ;; esac

need_command curl
need_command unzip
PYTHON=$(find_python)

BACKEND_URL=$(normalize_backend "$BACKEND_URL")
if [ -z "$APP_NAME" ]; then
  base_name=$(basename "$SOURCE_ZIP")
  APP_NAME="${base_name%.*}"
fi

TMP_DIR=$(mktemp -d 2>/dev/null || mktemp -d -t cloud-build)
trap 'rm -rf "$TMP_DIR"' EXIT
mkdir -p "$OUT_DIR"

info "Backend: $BACKEND_URL"
info "Preparing upload"
prepare_json=$(curl_json GET "$BACKEND_URL/prepare-upload?ext=zip")
job_id=$(printf '%s' "$prepare_json" | json_get job_id)
[ -n "$job_id" ] || fail "Backend did not return job_id"
info "Job ID: $job_id"

info "Uploading ZIP"
upload_json=$(curl -sS -X POST \
  -H 'Content-Type: application/zip' \
  --data-binary "@$SOURCE_ZIP" \
  "$BACKEND_URL/upload?job_id=$job_id")
upload_ok=$(printf '%s' "$upload_json" | json_get ok)
[ "$upload_ok" = "true" ] || fail "$(api_error "$upload_json" "Upload failed")"
asset_id=$(printf '%s' "$upload_json" | json_get asset_id)
[ -n "$asset_id" ] || fail "Backend did not return asset_id"
info "Uploaded asset ID: $asset_id"

app_name_json=$(printf '%s' "$APP_NAME" | json_escape)
package_name_json=$(printf '%s' "$PACKAGE_NAME" | json_escape)
build_mode_json=$(printf '%s' "$BUILD_MODE" | json_escape)
project_type_json=$(printf '%s' "$PROJECT_TYPE" | json_escape)

dispatch_body=$(cat <<EOF
{
  "job_id": "$job_id",
  "asset_id": "$asset_id",
  "app_name": $app_name_json,
  "package_name": $package_name_json,
  "build_mode": $build_mode_json,
  "project_type": $project_type_json
}
EOF
)

info "Dispatching build"
dispatch_json=$(curl_json POST "$BACKEND_URL/dispatch-job" "$dispatch_body")
dispatch_ok=$(printf '%s' "$dispatch_json" | json_get ok)
[ "$dispatch_ok" = "true" ] || fail "$(api_error "$dispatch_json" "Dispatch failed")"
run_id=$(printf '%s' "$dispatch_json" | json_get run_id)
run_number=$(printf '%s' "$dispatch_json" | json_get run_number)
info "Workflow run: ${run_id:-pending} ${run_number:+(#$run_number)}"

poll_count=0
status="queued"
conclusion=""
while [ "$poll_count" -lt "$MAX_POLLS" ]; do
  poll_count=$((poll_count + 1))
  if [ -n "$run_id" ]; then
    status_json=$(curl_json GET "$BACKEND_URL/status?run_id=$run_id&job_id=$job_id")
  else
    status_json=$(curl_json GET "$BACKEND_URL/status?job_id=$job_id")
    run_id=$(printf '%s' "$status_json" | json_get run_id)
  fi

  status=$(printf '%s' "$status_json" | json_get status)
  conclusion=$(printf '%s' "$status_json" | json_get conclusion)
  step_name=$(printf '%s' "$status_json" | json_get step_name)
  current_step=$(printf '%s' "$status_json" | json_get current_step)
  total_steps=$(printf '%s' "$status_json" | json_get total_steps)

  if [ -n "$step_name" ]; then
    info "Status: $status ${conclusion:+/$conclusion} - $step_name ${current_step:+($current_step/$total_steps)}"
  else
    info "Status: $status ${conclusion:+/$conclusion}"
  fi

  if [ "$status" = "completed" ]; then
    break
  fi
  sleep "$POLL_SECONDS"
done

[ "$status" = "completed" ] || fail "Timed out waiting for build to finish"

if [ "$conclusion" != "success" ]; then
  info "Build failed. Fetching logs"
  logs_json=$(curl_json GET "$BACKEND_URL/logs?run_id=$run_id&job_id=$job_id" || true)
  logs_file="$OUT_DIR/job_${job_id}_logs.txt"
  if [ -n "$logs_json" ]; then
    printf '%s' "$logs_json" | "$PYTHON" -c 'import json,sys
try:
    print(json.load(sys.stdin).get("log", ""))
except Exception:
    pass
' > "$logs_file"
    info "Logs saved: $logs_file"
  fi
  fail "Build completed with conclusion: $conclusion"
fi

info "Finding artifact"
artifact_json=$(curl_json GET "$BACKEND_URL/artifact-info?run_id=$run_id&job_id=$job_id&prefix=apk")
artifact_id=$(printf '%s' "$artifact_json" | json_get artifact_id)
artifact_name=$(printf '%s' "$artifact_json" | json_get name)
info "Artifact: $artifact_name ($artifact_id)"

artifact_zip="$OUT_DIR/${artifact_name:-apk-$job_id}.zip"
info "Downloading artifact ZIP"
curl_download "$BACKEND_URL/artifact?run_id=$run_id&job_id=$job_id&prefix=apk" "$artifact_zip"

extract_dir="$TMP_DIR/artifact"
mkdir -p "$extract_dir"
unzip -q "$artifact_zip" -d "$extract_dir"
apk_path=$(find "$extract_dir" -name '*.apk' -type f | head -n 1)
[ -n "$apk_path" ] || fail "No APK found inside artifact ZIP: $artifact_zip"

safe_app_name=$(printf '%s' "$APP_NAME" | tr -c 'A-Za-z0-9._-' '_')
output_apk="$OUT_DIR/${safe_app_name}_${BUILD_MODE}_${job_id}.apk"
cp "$apk_path" "$output_apk"

info "APK saved: $output_apk"
info "Artifact ZIP saved: $artifact_zip"

if [ -n "$artifact_id" ]; then
  curl -sS -X DELETE "$BACKEND_URL/delete-artifact?artifact_id=$artifact_id" >/dev/null || true
fi

info "Done"
