#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-install-test.XXXXXX")"
cleanup() {
  status=$?
  trap - EXIT
  if [[ $status -ne 0 && -f "$scratch/stderr" ]]; then
    sed -n '1,160p' "$scratch/stderr" >&2
  fi
  find "$scratch" -depth -delete
  exit "$status"
}
trap cleanup EXIT

mkdir -p "$scratch/bin" "$scratch/home"
cat >"$scratch/fake-kast" <<'SH'
#!/bin/sh
set -eu
if [ "${1:-}" = "__internal" ]; then
  printf '%s\n' "$*" >"$KAST_TEST_RESOURCE_ARGS"
  exit 0
fi
printf '%s\n' "$*" >"$KAST_TEST_SETUP_ARGS"
install_root="${KAST_HOME:-$HOME/.local/share/kast}/current"
user_bin="$HOME/.local/bin"
mkdir -p "$install_root/libexec" "$install_root/bin" "$user_bin"
cp "$0" "$install_root/libexec/kastctl"
cp "$0" "$install_root/bin/kast"
cp "$0" "$user_bin/kast"
chmod 755 "$install_root/libexec/kastctl" "$install_root/bin/kast" "$user_bin/kast"
printf '%s\n' 'type: KAST_SETUP' 'status: CURRENT'
SH
cat >"$scratch/bin/uname" <<'SH'
#!/bin/sh
if [ "$1" = "-s" ]; then
  printf '%s\n' "${KAST_TEST_OS:-Darwin}"
else
  printf '%s\n' "${KAST_TEST_ARCH:-arm64}"
fi
SH
cat >"$scratch/bin/curl" <<'SH'
#!/bin/sh
set -eu
output=""
url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output) output="$2"; shift 2 ;;
    *) url="$1"; shift ;;
  esac
done
printf '%s\n' "$url" >>"$KAST_TEST_CURL_LOG"
: >"$output"
SH
cat >"$scratch/bin/tar" <<'SH'
#!/bin/sh
set -eu
destination=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -C) destination="$2"; shift 2 ;;
    *) shift ;;
  esac
done
mkdir -p "$destination/bundle/bin" "$destination/bundle/libexec"
cp "$KAST_TEST_FAKE_CLI" "$destination/bundle/libexec/kastctl"
cp "$KAST_TEST_FAKE_CLI" "$destination/bundle/bin/kast"
chmod 755 "$destination/bundle/libexec/kastctl" "$destination/bundle/bin/kast"
SH
cat >"$scratch/bin/codex" <<'SH'
#!/bin/sh
exit 0
SH
for command in ps kill open; do
  cat >"$scratch/bin/$command" <<'SH'
#!/bin/sh
printf '%s\n' "$0 $*" >>"$KAST_TEST_FOREGROUND_CONTROL_LOG"
exit 99
SH
done
cat >"$scratch/fake-development-kast" <<'SH'
#!/bin/sh
printf '%s|%s\n' "$PWD" "$*" >"$KAST_TEST_RUNTIME_ARGS"
SH
chmod 755 "$scratch/fake-kast" "$scratch/fake-development-kast" "$scratch/bin/"*

export PATH="$scratch/bin:$PATH"
export HOME="$scratch/home"
export KAST_RELEASES_URL="https://releases.test"
export KAST_TEST_CURL_LOG="$scratch/curl.log"
export KAST_TEST_SETUP_ARGS="$scratch/setup.args"
export KAST_TEST_RESOURCE_ARGS="$scratch/resources.args"
export KAST_TEST_FAKE_CLI="$scratch/fake-kast"
export KAST_TEST_FOREGROUND_CONTROL_LOG="$scratch/foreground-control.log"
export KAST_TEST_RUNTIME_ARGS="$scratch/runtime.args"
unset NO_COLOR
export CLICOLOR_FORCE=1

development_repo="$scratch/kast-repository"
development_home="$scratch/development-home"
mkdir -p "$development_repo" "$development_home"
development_repo="$(cd -- "$development_repo" && pwd -P)"
development_home="$(cd -- "$development_home" && pwd -P)"
cp "$repo_root/install.sh" "$development_repo/install.sh"
printf '%s\n' 'rootProject.name = "kast"' >"$development_repo/settings.gradle.kts"
cat >"$development_repo/gradlew" <<'SH'
#!/bin/sh
set -eu
printf '%s\n' "$*" >"$KAST_TEST_GRADLE_ARGS"
mkdir -p "$KAST_HOME/current/bin" "$KAST_HOME/current/libexec"
cp "$KAST_TEST_DEVELOPMENT_AGENT" "$KAST_HOME/current/bin/kast"
cp "$KAST_TEST_DEVELOPMENT_AGENT" "$KAST_HOME/current/libexec/kastctl"
chmod 755 "$KAST_HOME/current/bin/kast" "$KAST_HOME/current/libexec/kastctl"
SH
chmod 755 "$development_repo/install.sh" "$development_repo/gradlew"
git init -q "$development_repo"
git -C "$development_repo" add install.sh gradlew settings.gradle.kts

bash "$development_repo/install.sh" --help >"$scratch/development-help.stdout" 2>"$scratch/development-help.stderr"
grep -Fq -- '--development' "$scratch/development-help.stderr"
grep -Fq -- '--clean' "$scratch/development-help.stderr"
if grep -Eq -- '--configure|--autostart|--config-defaults' "$scratch/development-help.stderr"; then
  printf '%s\n' 'retired foreground IDEA options remain in installer help' >&2
  exit 1
fi

mkdir -p "$scratch/outside-repository"
cp "$repo_root/install.sh" "$scratch/outside-repository/install.sh"
bash "$scratch/outside-repository/install.sh" --help >"$scratch/outside-help.stdout" 2>"$scratch/outside-help.stderr"
if grep -Eq -- '--development|--clean' "$scratch/outside-help.stderr"; then
  printf '%s\n' 'development-only options leaked outside the Kast Git repository' >&2
  exit 1
fi

: >"$scratch/gradle.args"
: >"$KAST_TEST_RUNTIME_ARGS"
KAST_HOME="$development_home" \
  KAST_TEST_GRADLE_ARGS="$scratch/gradle.args" \
  KAST_TEST_DEVELOPMENT_AGENT="$scratch/fake-development-kast" \
  bash "$development_repo/install.sh" --development --harness none >"$scratch/stdout" 2>"$scratch/stderr"
grep -Fqx -- "--project-dir $development_repo refreshDevelopmentMachine --no-daemon --console=plain" \
  "$scratch/gradle.args"
grep -Fqx "$development_repo|up" "$KAST_TEST_RUNTIME_ARGS"
grep -Fq 'Repository database ready' "$scratch/stderr"

: >"$scratch/gradle.args"
KAST_HOME="$development_home" \
  KAST_TEST_GRADLE_ARGS="$scratch/gradle.args" \
  KAST_TEST_DEVELOPMENT_AGENT="$scratch/fake-development-kast" \
  bash "$development_repo/install.sh" --development --clean --harness none >"$scratch/stdout" 2>"$scratch/stderr"
grep -Fqx -- "--project-dir $development_repo -PkastDevelopmentClean=true refreshDevelopmentMachine --no-daemon --console=plain" \
  "$scratch/gradle.args"
if grep -Eq -- '(^| )(clean|--rerun-tasks)( |$)' "$scratch/gradle.args"; then
  printf '%s\n' 'development clean deleted or bypassed build-time caches' >&2
  exit 1
fi

: >"$scratch/curl.log"
: >"$KAST_TEST_FOREGROUND_CONTROL_LOG"
bash "$repo_root/install.sh" --version v1.2.3 >"$scratch/stdout" 2>"$scratch/stderr"
grep -Fqx 'https://releases.test/download/v1.2.3/kast-macos-arm64-v1.2.3.tar.gz' "$scratch/curl.log"
if grep -Fq 'kast-idea-' "$scratch/curl.log"; then
  printf '%s\n' 'installer downloaded the retired public IDEA plugin' >&2
  exit 1
fi
grep -Eq '^setup --source .*/bundle$' "$scratch/setup.args"
grep -Fq '__internal resources install --harness codex' "$scratch/resources.args"
[[ ! -s "$KAST_TEST_FOREGROUND_CONTROL_LOG" ]]
grep -Fq $'\033[1;36m◆ KAST INSTALLER\033[0m' "$scratch/stderr"
grep -Fq $'\033[36m◆\033[0m Downloading Kast bundle' "$scratch/stderr"
grep -Fq $'\033[32m✓\033[0m Kast is ready' "$scratch/stderr"
[[ ! -s "$scratch/stdout" ]]

bash "$repo_root/install.sh" --version v1.2.3 --force >"$scratch/stdout" 2>"$scratch/stderr"
grep -Eq '^setup --source .*/bundle --force$' "$scratch/setup.args"

: >"$scratch/curl.log"
KAST_TEST_OS=Linux KAST_TEST_ARCH=x86_64 \
  bash "$repo_root/install.sh" --version v1.2.3 >"$scratch/stdout" 2>"$scratch/stderr"
grep -Fqx 'https://releases.test/download/v1.2.3/kast-linux-x64-v1.2.3.tar.gz' "$scratch/curl.log"

if bash "$repo_root/install.sh" --configure >"$scratch/stdout" 2>"$scratch/stderr"; then
  printf '%s\n' 'retired installer configuration option was accepted' >&2
  exit 1
fi
grep -Fq 'unknown argument: --configure' "$scratch/stderr"

NO_COLOR=1 CLICOLOR_FORCE= \
  bash "$repo_root/install.sh" --version v1.2.3 >"$scratch/stdout" 2>"$scratch/plain.stderr"
if LC_ALL=C grep -q $'\033' "$scratch/plain.stderr"; then
  printf '%s\n' 'redirected output contains terminal color sequences' >&2
  exit 1
fi

printf '%s\n' 'headless-only macOS installer contract passed'
