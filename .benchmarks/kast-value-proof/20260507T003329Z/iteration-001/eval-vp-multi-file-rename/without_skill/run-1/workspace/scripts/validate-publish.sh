#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

BOLD='\033[1m'
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

VALIDATION_FAILED=0
TARGET="${1:-${PUBLISH_TARGET:-release}}"
SKIP_CREDENTIAL_CHECKS="${VALIDATE_PUBLISH_SKIP_CREDENTIALS:-0}"
SKIP_GRADLE_SMOKE="${VALIDATE_PUBLISH_SKIP_SMOKE:-0}"

pass() { echo -e "  ${GREEN}[OK]${NC} $1"; }
warn() { echo -e "  ${YELLOW}[WARN]${NC} $1"; }
fail() {
  echo -e "  ${RED}[FAIL]${NC} $1"
  VALIDATION_FAILED=1
}

value_from_props() {
  local key="$1"
  local file="$2"
  grep -E "^${key}=" "$file" 2>/dev/null | head -n1 | cut -d'=' -f2- || true
}

validate_target() {
  case "$TARGET" in
    local|snapshot|release|github) ;;
    *)
      echo "Invalid publish target '$TARGET'. Expected: local|snapshot|release|github" >&2
      exit 1
      ;;
  esac
}

needs_signing() {
  [[ "$TARGET" == "snapshot" || "$TARGET" == "release" ]]
}

needs_maven_central_credentials() {
  [[ "$TARGET" == "snapshot" || "$TARGET" == "release" ]]
}

needs_github_credentials() {
  [[ "$TARGET" == "github" ]]
}

check_version_for_target() {
  local version="$1"

  case "$TARGET" in
    snapshot)
      if [[ "$version" != *"-SNAPSHOT" ]]; then
        fail "snapshot publish requires version to end with -SNAPSHOT (found $version)"
      else
        pass "snapshot version detected: $version"
      fi
      ;;
    release)
      if [[ "$version" == *"-SNAPSHOT" ]]; then
        fail "release publish requires non-SNAPSHOT version (found $version)"
      else
        pass "release version detected: $version"
      fi
      ;;
    local|github)
      pass "version accepted for $TARGET publish: $version"
      ;;
  esac
}

check_publishable_modules() {
  local found_any=0

  while IFS= read -r build_file; do
    found_any=1
    local module_dir
    module_dir="$(dirname "$build_file")"

    if [[ -d "$module_dir" ]]; then
      pass "publishable module: $module_dir"
    else
      fail "publishable module directory missing: $module_dir"
    fi
  done < <(rg -l 'id\("konditional\.(publishing|published-library)"\)' --glob '*/build.gradle.kts' | sort -u)

  if [[ "$found_any" -eq 0 ]]; then
    fail "no publishable modules found (expected at least one build.gradle.kts with konditional.publishing or konditional.published-library)"
  fi
}

run_gradle_validation_nodes() {
  if make publish-gradle-validate TARGET="$TARGET" >/dev/null; then
    pass "Gradle publish task resolution succeeded for target '$TARGET'"
  else
    fail "Gradle publish task resolution failed for target '$TARGET'"
  fi

  if [[ "$SKIP_GRADLE_SMOKE" == "1" ]]; then
    warn "Skipping Gradle publish smoke check (VALIDATE_PUBLISH_SKIP_SMOKE=1)"
    return
  fi

  if make publish-gradle-smoke TARGET="$TARGET" >/dev/null; then
    pass "Gradle publish smoke check succeeded for target '$TARGET'"
  else
    fail "Gradle publish smoke check failed for target '$TARGET'"
  fi
}

validate_target

echo -e "${BOLD}${BLUE}==============================================================================${NC}"
echo -e "${BOLD}${BLUE}Konditional Publish Validation (${TARGET^^})${NC}"
echo -e "${BOLD}${BLUE}==============================================================================${NC}"
echo ""

# 1. gradle.properties + version

echo -e "${BOLD}[1/7] Checking gradle.properties and target version constraints...${NC}"
if [[ ! -f "gradle.properties" ]]; then
  fail "gradle.properties not found"
else
  VERSION="$(value_from_props "version" "gradle.properties")"
  if [[ -z "$VERSION" ]]; then
    VERSION="$(value_from_props "VERSION" "gradle.properties")"
  fi
  GROUP="$(value_from_props "GROUP" "gradle.properties")"

  [[ -n "$VERSION" ]] && pass "version=$VERSION" || fail "Missing version in gradle.properties"
  [[ -n "$GROUP" ]] && pass "GROUP=$GROUP" || fail "Missing GROUP in gradle.properties"

  if [[ -n "$VERSION" ]]; then
    check_version_for_target "$VERSION"
  fi
fi
echo ""

# 2. git status

echo -e "${BOLD}[2/7] Checking git working tree...${NC}"
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  fail "Not a Git repository"
else
  if [[ -n "$(git status --porcelain)" ]]; then
    warn "Uncommitted changes detected"
  else
    pass "Working tree clean"
  fi

  CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
  pass "Current branch: $CURRENT_BRANCH"
fi
echo ""

# 3. signing credentials

echo -e "${BOLD}[3/7] Checking signing credentials (if required)...${NC}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
USER_GRADLE_PROPS="$GRADLE_USER_HOME/gradle.properties"

if [[ "$SKIP_CREDENTIAL_CHECKS" == "1" ]]; then
  warn "Skipping credential checks (VALIDATE_PUBLISH_SKIP_CREDENTIALS=1)"
elif needs_signing; then
  if [[ ! -f "$USER_GRADLE_PROPS" ]]; then
    fail "~/.gradle/gradle.properties not found"
  else
    SIGNING_GPG_KEY_NAME="$(value_from_props "signing.gnupg.keyName" "$USER_GRADLE_PROPS")"
    SIGNING_KEY_ID="$(value_from_props "signing.keyId" "$USER_GRADLE_PROPS")"

    if [[ -n "$SIGNING_GPG_KEY_NAME" ]]; then
      pass "GPG command signing configured"
    elif [[ -n "$SIGNING_KEY_ID" ]]; then
      pass "Legacy keyring signing configured"
    else
      fail "No signing credentials configured for target '$TARGET'"
    fi
  fi
else
  pass "Signing credentials not required for target '$TARGET'"
fi
echo ""

# 4. repository credentials

echo -e "${BOLD}[4/7] Checking repository credentials (if required)...${NC}"
if [[ "$SKIP_CREDENTIAL_CHECKS" == "1" ]]; then
  warn "Skipping repository credential checks (VALIDATE_PUBLISH_SKIP_CREDENTIALS=1)"
elif [[ ! -f "$USER_GRADLE_PROPS" ]]; then
  if needs_maven_central_credentials || needs_github_credentials; then
    fail "~/.gradle/gradle.properties not found"
  else
    pass "No repository credentials required for target '$TARGET'"
  fi
else
  OSSRH_USERNAME="$(value_from_props "ossrhUsername" "$USER_GRADLE_PROPS")"
  OSSRH_PASSWORD="$(value_from_props "ossrhPassword" "$USER_GRADLE_PROPS")"
  MAVEN_CENTRAL_USERNAME="$(value_from_props "mavenCentralUsername" "$USER_GRADLE_PROPS")"
  MAVEN_CENTRAL_PASSWORD="$(value_from_props "mavenCentralPassword" "$USER_GRADLE_PROPS")"

  GPR_USER="$(value_from_props "gpr.user" "$USER_GRADLE_PROPS")"
  GPR_KEY="$(value_from_props "gpr.key" "$USER_GRADLE_PROPS")"

  if needs_maven_central_credentials; then
    if [[ -n "$OSSRH_USERNAME" && -n "$OSSRH_PASSWORD" ]]; then
      pass "Maven Central credentials configured via ossrhUsername/ossrhPassword"
    elif [[ -n "$MAVEN_CENTRAL_USERNAME" && -n "$MAVEN_CENTRAL_PASSWORD" ]]; then
      pass "Maven Central credentials configured via mavenCentralUsername/mavenCentralPassword"
    else
      fail "Missing Maven Central credentials for target '$TARGET'"
    fi
  else
    pass "Maven Central credentials not required for target '$TARGET'"
  fi

  if needs_github_credentials; then
    if [[ -n "$GPR_USER" && -n "$GPR_KEY" ]]; then
      pass "GitHub Packages credentials configured via gpr.user/gpr.key"
    elif [[ -n "${GITHUB_ACTOR:-}" && -n "${GITHUB_TOKEN:-}" ]]; then
      pass "GitHub Packages credentials configured via GITHUB_ACTOR/GITHUB_TOKEN"
    else
      fail "Missing GitHub Packages credentials for target '$TARGET'"
    fi
  else
    pass "GitHub Packages credentials not required for target '$TARGET'"
  fi
fi
echo ""

# 5. publishable modules

echo -e "${BOLD}[5/7] Discovering publishable modules...${NC}"
check_publishable_modules
echo ""

# 6. Gradle task graph and smoke checks via Makefile nodes

echo -e "${BOLD}[6/7] Validating Gradle publishing nodes via Makefile...${NC}"
run_gradle_validation_nodes
echo ""

# 7. Final summary

echo -e "${BOLD}[7/7] Summary${NC}"
echo -e "${BOLD}${BLUE}==============================================================================${NC}"
if [[ "$VALIDATION_FAILED" -eq 0 ]]; then
  echo -e "${BOLD}${GREEN}All publish validations passed for target '$TARGET'.${NC}"
  echo -e "Run: ${BOLD}make publish${NC}"
  exit 0
else
  echo -e "${BOLD}${RED}Publish validation failed for target '$TARGET'.${NC}"
  exit 1
fi
