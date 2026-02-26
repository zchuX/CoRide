#!/usr/bin/env bash
set -euo pipefail

# Simple API caller that uses persisted stage info from scripts/.api/<stage>.json
# Examples:
#   ./scripts/easy-api.sh --stage dev auth/register '{"username":"alice","password":"hunter2","email":"alice@example.com"}'
#   ./scripts/easy-api.sh --stage dev auth/register @payload.json
# Workflow mode:
#   ./scripts/easy-api.sh --stage dev workflow login-create-trip --email alice@example.com --password p@ss \
#     --start "Home" --dest "Work" --group-name "Friends" --driver-confirmed true

STAGE=""
METHOD="POST"
SOURCE="apigw" # or 'cloudfront'
declare -a EXTRA_HEADERS=()

usage() {
  cat <<USAGE
Usage: $0 --stage <dev|staging|prod> <path> <json>

Examples:
  $0 --stage dev auth/register '{"username":"alice","password":"p@ss","email":"alice@example.com"}'
  $0 --stage dev auth/register @payload.json

Options:
  --stage     Target stage to use (dev|staging|prod)
  --method    HTTP method (default POST)
  --source    apigw|cloudfront (default apigw). If cloudfront is persisted, you can call via CDN.
  --header    Additional header, can be used multiple times (e.g. --header 'Authorization: Bearer <token>')

Workflow mode:
  $0 --stage <stage> workflow login-create-trip [options]
  Options:
    --email <email> | --phone <E.164>   Login identifier (one required)
    --password <password>               Login password (required)
    --status <status>                   Trip status (default: Upcoming)
    --start <location>                  Group start (default: Home)
    --dest <location>                   Group destination (default: Work)
    --pickup <epochMillis>              Group pickup time (default: now+1h)
    --group-name <name>                 Group name (default: Friends)
    --driver-confirmed true|false       Mark caller as driver and confirmed (default: false)
    --notes <text>                      Trip notes (optional)
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --stage)
      STAGE="$2"; shift 2;;
    --method)
      METHOD="$2"; shift 2;;
    --source)
      SOURCE="$2"; shift 2;;
    --header)
      EXTRA_HEADERS+=("$2"); shift 2;;
    -h|--help)
      usage; exit 0;;
    *)
      break;;
  esac
done

if [[ -z "${STAGE}" ]]; then
  echo "Error: --stage is required" >&2
  usage
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
INFO_FILE="$ROOT_DIR/scripts/.api/${STAGE}.json"

# ------------- Helpers -------------
require_jq() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "Error: jq is required for workflow mode. Install jq and retry." >&2
    exit 1
  fi
}

api_info() {
  # Reads BASE_URL and API_KEY based on --source
  if command -v jq >/dev/null 2>&1; then
    API_KEY=$(jq -r '.apiKeyValue' "$INFO_FILE")
    if [[ "$SOURCE" == "cloudfront" ]]; then
      BASE_URL=$(jq -r '.cloudFrontBaseUrl' "$INFO_FILE")
    else
      BASE_URL=$(jq -r '.baseUrl' "$INFO_FILE")
    fi
  else
    API_KEY=$(grep '"apiKeyValue"' "$INFO_FILE" | sed -E 's/.*"apiKeyValue"\s*:\s*"([^"]+)".*/\1/')
    if [[ "$SOURCE" == "cloudfront" ]]; then
      BASE_URL=$(grep '"cloudFrontBaseUrl"' "$INFO_FILE" | sed -E 's/.*"cloudFrontBaseUrl"\s*:\s*"([^"]+)".*/\1/')
    else
      BASE_URL=$(grep '"baseUrl"' "$INFO_FILE" | sed -E 's/.*"baseUrl"\s*:\s*"([^"]+)".*/\1/')
    fi
  fi
  if [[ -z "$BASE_URL" || "$BASE_URL" == "null" ]]; then
    echo "Error: baseUrl missing in $INFO_FILE" >&2
    exit 1
  fi
}

call_api() {
  local method="$1"; shift
  local path="$1"; shift
  local payload="$1"; shift
  local url="${BASE_URL%/}/${path}"
  local -a cmd=(curl -sS -X "$method" -H "Content-Type: application/json")
  if [[ -n "${API_KEY:-}" && "${API_KEY:-}" != "null" ]]; then
    cmd+=( -H "x-api-key: $API_KEY" )
  fi
  # Pass any extra headers from global flags
  if [[ ${#EXTRA_HEADERS[@]} -gt 0 ]]; then
    for h in "${EXTRA_HEADERS[@]}"; do cmd+=( -H "$h" ); done
  fi
  cmd+=( "$url" )
  # Only attach a body for methods that typically support it
  if [[ "$method" != "GET" && "$method" != "DELETE" ]]; then
    cmd+=( --data "$payload" )
  fi
  echo "[+] Calling: $method $url" >&2
  "${cmd[@]}"
}

auth_header() {
  local token="$1"
  echo "Authorization: Bearer $token"
}

gen_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    # Fallback: 32 hex chars
    openssl rand -hex 16
  fi
}

now_ms() {
  python - <<'PY'
import time
print(int(time.time()*1000))
PY
}

workflow_login_create_trip() {
  require_jq
  api_info

  local email="" phone="" username="" password="" status="Upcoming" start="Home" dest="Work" pickup="" group_name="Friends" driver_confirmed="false" notes=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --email) email="$2"; shift 2;;
      --phone) phone="$2"; shift 2;;
      --username) username="$2"; shift 2;;
      --password) password="$2"; shift 2;;
      --status) status="$2"; shift 2;;
      --start) start="$2"; shift 2;;
      --dest) dest="$2"; shift 2;;
      --pickup) pickup="$2"; shift 2;;
      --group-name) group_name="$2"; shift 2;;
      --driver-confirmed) driver_confirmed="$2"; shift 2;;
      --notes) notes="$2"; shift 2;;
      *) echo "Unknown option for workflow: $1" >&2; exit 1;;
    esac
  done
  # Require exactly one of email, phone, or username
  local id_count=0
  [[ -n "$email" ]] && ((id_count++)) || true
  [[ -n "$phone" ]] && ((id_count++)) || true
  [[ -n "$username" ]] && ((id_count++)) || true
  if [[ -z "$password" || $id_count -ne 1 ]]; then
    echo "Error: provide --password and exactly one of --email, --phone, or --username" >&2
    exit 1
  fi
  if [[ -z "$pickup" ]]; then pickup=$(($(now_ms)+3600000)); fi

  # 1) Login
  local login_payload
  if [[ -n "$email" ]]; then
    login_payload=$(printf '{"email":"%s","password":"%s"}' "$email" "$password")
  elif [[ -n "$phone" ]]; then
    login_payload=$(printf '{"phone_number":"%s","password":"%s"}' "$phone" "$password")
  else
    login_payload=$(printf '{"username":"%s","password":"%s"}' "$username" "$password")
  fi
  local login_res
  login_res=$(call_api POST "auth/login" "$login_payload")
  local id_token
  id_token=$(echo "$login_res" | jq -r '.idToken // .id_token // empty')
  if [[ -z "$id_token" || "$id_token" == "null" ]]; then
    echo "Login failed: $login_res" >&2
    exit 1
  fi
  echo "[+] Login OK" >&2

  # 2) Get /auth/me for userArn and name
  local me_res
  EXTRA_HEADERS+=("$(auth_header "$id_token")")
  me_res=$(call_api GET "auth/me" '{}')
  # remove the temp header we added for /auth/me to avoid duplicating later
  unset 'EXTRA_HEADERS[${#EXTRA_HEADERS[@]}-1]'
  # Ensure JSON; if not, attempt lenient fix for bare string fields, else print and exit
  if ! echo "$me_res" | jq -e . >/dev/null 2>&1; then
    # Try to quote unquoted string values for userArn and name
    local me_fixed
    me_fixed=$(echo "$me_res" | sed -E 's/"userArn":([^",}{]+)/"userArn":"\1"/g; s/"name":([^",}{]+)/"name":"\1"/g')
    if echo "$me_fixed" | jq -e . >/dev/null 2>&1; then
      me_res="$me_fixed"
    else
      echo "Error: /auth/me returned non-JSON response:" >&2
      echo "$me_res" >&2
      exit 1
    fi
  fi
  local user_arn user_name
  user_arn=$(echo "$me_res" | jq -r '.user.userArn // .userArn // .user.userARN // empty')
  user_name=$(echo "$me_res" | jq -r '.user.name // .name // empty')
  if [[ -z "$user_arn" || "$user_arn" == "null" ]]; then
    echo "Failed to resolve userArn from /auth/me: $me_res" >&2
    exit 1
  fi

  # 3) Build create trip payload
  local trip_uuid group_uuid trip_arn group_arn now
  trip_uuid=$(gen_uuid)
  group_uuid=$(gen_uuid)
  trip_arn="trip:$trip_uuid"
  group_arn="group:$group_uuid"
  now=$(now_ms)
  local car_json='{"plateNumber":null,"color":null,"model":null}'
  local driver_json
  if [[ "$driver_confirmed" == "true" ]]; then
    driver_json=$(printf '"driver":"%s","driverConfirmed":true,' "$user_arn")
  else
    driver_json=""
  fi
  local notes_json
  if [[ -n "$notes" ]]; then
    notes_json=$(printf ',"notes":"%s"' "$notes")
  else
    notes_json=""
  fi
  local create_payload
  create_payload=$(cat <<JSON
{
  "tripArn":"$trip_arn",
  "startTime": $now,
  "status":"$status",
  ${driver_json}
  "car": $car_json,
  "groups": [
    {
      "arn":"$group_arn",
      "groupName":"$group_name",
      "start":"$start",
      "destination":"$dest",
      "pickupTime": $pickup,
      "users":[{"userId":"$user_arn","name":"$user_name","accept":false}]
    }
  ]
  ${notes_json}
}
JSON
)

  # 4) Create trip with auth header
  EXTRA_HEADERS+=("$(auth_header "$id_token")")
  local create_res
  create_res=$(call_api POST "api/trips" "$create_payload")
  echo "$create_res"
}

if [[ ! -f "$INFO_FILE" ]]; then
  echo "Error: stage info file not found: $INFO_FILE" >&2
  echo "Hint: run scripts/deploy.sh --stage ${STAGE} (infra deploy) to persist API info." >&2
  exit 1
fi

# If the first arg is 'workflow', run a workflow
if [[ $# -gt 0 && "$1" == "workflow" ]]; then
  shift
  wf_name="${1:-}"; shift || true
  case "$wf_name" in
    login-create-trip)
      workflow_login_create_trip "$@";;
    *)
      echo "Unknown workflow: $wf_name" >&2; exit 1;;
  esac
  exit 0
fi

# Legacy simple call mode requires <path> <json>
if [[ $# -lt 2 ]]; then
  echo "Error: path and json payload are required" >&2
  usage
  exit 1
fi

PATH_SEG="$1"
PAYLOAD="$2"

api_info

if [[ -z "${API_KEY:-}" || "${API_KEY:-}" == "null" ]]; then
  echo "Warning: apiKeyValue missing; calling without x-api-key" >&2
fi

URL="${BASE_URL%/}/${PATH_SEG}"

curl_cmd=(
  curl -sS \
    -X "$METHOD" \
    -H "Content-Type: application/json" \
    "$URL"
)

# Add API key header if present
if [[ -n "${API_KEY:-}" && "${API_KEY:-}" != "null" ]]; then
  curl_cmd+=( -H "x-api-key: $API_KEY" )
fi

# Add any extra headers provided
if [[ ${#EXTRA_HEADERS[@]} -gt 0 ]]; then
  for h in "${EXTRA_HEADERS[@]}"; do
    curl_cmd+=( -H "$h" )
  done
fi

# Payload: allow @file.json or raw JSON
curl_cmd+=( --data "$PAYLOAD" )

echo "[+] Calling: $METHOD $URL"
"${curl_cmd[@]}"