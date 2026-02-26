#!/usr/bin/env bash
set -euo pipefail

# Usage:
# scripts/deploy.sh --stage dev|staging|prod [--build-scala all|handler|daos|none] [--deploy all|infra|code|none] [--recreate]
# Defaults: --build-scala all, --deploy all

STAGE=""
BUILD_SCALA="all"
DEPLOY_MODE="all"
RECREATE="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --stage)
      STAGE="$2"; shift 2;;
    --build-scala)
      BUILD_SCALA="$2"; shift 2;;
    --deploy)
      DEPLOY_MODE="$2"; shift 2;;
    --recreate)
      RECREATE="true"; shift 1;;
    -h|--help)
      echo "Usage: $0 --stage dev|staging|prod [--build-scala all|handler|daos|none] [--deploy all|infra|code|none] [--recreate]"
      exit 0;;
    *)
      echo "Unknown argument: $1"; exit 1;;
  esac
done

if [[ -z "$STAGE" ]]; then
  echo "Error: --stage is required (dev|staging|prod)"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

build_project() {
  local dir="$1"
  local publish_after="${2:-false}"
  echo "[+] Running tests in $dir"
  (cd "$dir" && sbt -batch test)
  echo "[+] Building assembly in $dir"
  (cd "$dir" && sbt -batch assembly)
  if [[ "$publish_after" == "true" ]]; then
    echo "[+] Publishing $dir to local Ivy (so handler assembly picks up changes)"
    (cd "$dir" && sbt -batch publishLocal)
  fi
}

if [[ "$BUILD_SCALA" != "none" ]]; then
  case "$BUILD_SCALA" in
    all)
      build_project "$ROOT_DIR/tripDAO" true
      build_project "$ROOT_DIR/userDAO" true
      build_project "$ROOT_DIR/rateLimitDAO" true
      build_project "$ROOT_DIR/corrideLambdaHandler" false
      ;;
    handler)
      build_project "$ROOT_DIR/corrideLambdaHandler" false
      ;;
    daos)
      build_project "$ROOT_DIR/tripDAO" true
      build_project "$ROOT_DIR/userDAO" true
      build_project "$ROOT_DIR/rateLimitDAO" true
      ;;
    *)
      echo "Unknown --build-scala option: $BUILD_SCALA"; exit 1;;
  esac
else
  echo "[+] Skipping Scala build/tests"
fi

cdk_dir=""
case "$STAGE" in
  dev)
    cdk_dir="$ROOT_DIR/corideCDK-dev";;
  staging)
    cdk_dir="$ROOT_DIR/coRideCDK-staging";;
  prod)
    cdk_dir="$ROOT_DIR/coRideCDK-prod";;
  *)
    echo "Unknown stage: $STAGE"; exit 1;;
esac

if [[ "$DEPLOY_MODE" == "all" || "$DEPLOY_MODE" == "infra" ]]; then
  echo "[+] Installing and building CDK app in $cdk_dir"
  (cd "$cdk_dir" && npm install && npm run build)
  if [[ "$RECREATE" == "true" ]]; then
    echo "[!] Recreate flag set: Destroying stacks for stage $STAGE"
    # Destroy all stacks to allow immutable property changes (e.g., Cognito alias settings)
    (cd "$cdk_dir" && npx cdk destroy --all --force)
  fi
  echo "[+] Deploying CDK stacks for stage $STAGE (all stacks)"
  (cd "$cdk_dir" && npx cdk deploy --all --require-approval never)

  echo "[+] Persisting API endpoint and key for stage $STAGE"
  # Resolve region for AWS CLI queries: prefer env, else infer from stage
  if [[ -n "${AWS_REGION:-}" ]]; then
    REGION="${AWS_REGION}"
  elif [[ -n "${AWS_DEFAULT_REGION:-}" ]]; then
    REGION="${AWS_DEFAULT_REGION}"
  else
    case "$STAGE" in
      dev) REGION="us-east-1";;
      staging) REGION="us-west-1";;
      prod) REGION="us-west-2";;
      *) REGION="us-east-1";;
    esac
  fi
  mkdir -p "$ROOT_DIR/scripts/.api"

  # Find REST API ID by name
  REST_API_ID=$(aws apigateway get-rest-apis \
    --query "items[?name=='CorideApi-${STAGE}'].id | [0]" \
    --output text --region "$REGION")

  if [[ -z "$REST_API_ID" || "$REST_API_ID" == "None" ]]; then
    echo "[!] Could not locate RestApiId for stage $STAGE in region $REGION" >&2
  else
    BASE_URL="https://${REST_API_ID}.execute-api.${REGION}.amazonaws.com/${STAGE}"

    # Find Usage Plan that includes this API and stage
    USAGE_PLAN_ID=$(aws apigateway get-usage-plans \
      --query "items[?length(apiStages[?apiId=='${REST_API_ID}' && stage=='${STAGE}']) > \`0\`].id | [0]" \
      --output text --region "$REGION")

    API_KEY_VALUE=""
    if [[ -n "$USAGE_PLAN_ID" && "$USAGE_PLAN_ID" != "None" ]]; then
      # Get first key on the usage plan (include value)
      API_KEY_ID=$(aws apigateway get-usage-plan-keys --usage-plan-id "$USAGE_PLAN_ID" \
        --query "items[0].id" --output text --region "$REGION" || true)
      if [[ -n "$API_KEY_ID" && "$API_KEY_ID" != "None" ]]; then
        API_KEY_VALUE=$(aws apigateway get-api-key --api-key "$API_KEY_ID" --include-value \
          --query 'value' --output text --region "$REGION" || true)
      fi
    fi

    # Try to resolve CloudFront Distribution from the edge stack resources
    STACK_NAME="coride-edge-${STAGE}"
    CF_DIST_ID=$(aws cloudformation list-stack-resources --stack-name "$STACK_NAME" \
      --query "StackResourceSummaries[?ResourceType=='AWS::CloudFront::Distribution'].PhysicalResourceId | [0]" \
      --output text --region "$REGION" || true)

    CF_DOMAIN=""
    CF_BASE_URL=""
    if [[ -n "$CF_DIST_ID" && "$CF_DIST_ID" != "None" ]]; then
      CF_DOMAIN=$(aws cloudfront get-distribution --id "$CF_DIST_ID" \
        --query 'Distribution.DomainName' --output text || true)
      if [[ -n "$CF_DOMAIN" && "$CF_DOMAIN" != "None" ]]; then
        CF_BASE_URL="https://${CF_DOMAIN}"
      fi
    fi

    cat > "$ROOT_DIR/scripts/.api/${STAGE}.json" <<JSON
{
  "stage": "${STAGE}",
  "region": "${REGION}",
  "restApiId": "${REST_API_ID}",
  "baseUrl": "${BASE_URL}",
  "usagePlanId": "${USAGE_PLAN_ID:-}",
  "apiKeyValue": "${API_KEY_VALUE:-}",
  "cloudFrontDistributionId": "${CF_DIST_ID:-}",
  "cloudFrontDomainName": "${CF_DOMAIN:-}",
  "cloudFrontBaseUrl": "${CF_BASE_URL:-}"
}
JSON
    echo "[✓] Persisted: $ROOT_DIR/scripts/.api/${STAGE}.json"
  fi
else
  echo "[+] Skipping CDK deployment (mode: $DEPLOY_MODE)"
fi

echo "[✓] Done. Stage=$STAGE BuildScala=$BUILD_SCALA Deploy=$DEPLOY_MODE"