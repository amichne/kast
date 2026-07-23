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
printf '%s\n' '#!/bin/sh' 'if [ "$1" = "-s" ]; then printf "%s\\n" "${KAST_TEST_OS:-Darwin}"; else printf "%s\\n" "${KAST_TEST_ARCH:-arm64}"; fi' > "$scratch/bin/uname"
printf '%s\n' '#!/bin/sh' 'output=""' 'url=""' 'while [ "$#" -gt 0 ]; do case "$1" in --output) output="$2"; shift 2 ;; *) url="$1"; shift ;; esac; done' 'printf "%s\\n" "$url" >> "$KAST_TEST_CURL_LOG"' ': > "$output"' > "$scratch/bin/curl"
printf '%s\n' '#!/bin/sh' 'destination=""' 'while [ "$#" -gt 0 ]; do case "$1" in -d) destination="$2"; shift 2 ;; *) shift ;; esac; done' 'mkdir -p "$destination"' 'printf "%s\n" "#!/bin/sh" "printf \"%s\\n\" \"\$*\" > \"\$KAST_TEST_SETUP_ARGS\"" "while [ \"\$#\" -gt 0 ]; do case \"\$1\" in --config-defaults) cp \"\$2\" \"\$KAST_TEST_CONFIG_DEFAULTS\"; shift 2 ;; *) shift ;; esac; done" "printf \"%s\\n\" \"type: KAST_SETUP\" \"status: CURRENT\"" > "$destination/kast"' 'chmod 755 "$destination/kast"' > "$scratch/bin/unzip"
printf '%s\n' '#!/bin/sh' 'destination=""' 'while [ "$#" -gt 0 ]; do case "$1" in -C) destination="$2"; shift 2 ;; *) shift ;; esac; done' 'mkdir -p "$destination/bundle/bin"' 'printf "%s\n" "#!/bin/sh" "printf \"%s\\n\" \"\$*\" > \"\$KAST_TEST_SETUP_ARGS\"" > "$destination/bundle/bin/kast"' 'chmod 755 "$destination/bundle/bin/kast"' > "$scratch/bin/tar"
printf '%s\n' '#!/bin/sh' 'printf "%s\n" "$*" >> "$KAST_TEST_CODEX_LOG"' > "$scratch/bin/codex"
printf '%s\n' '#!/bin/sh' 'if [ "${KAST_TEST_IDEA_RUNNING:-0}" = 1 ] && [ ! -f "$KAST_TEST_IDEA_CLOSED" ]; then printf "%s\n" "4312 /Applications/IntelliJ IDEA.app/Contents/MacOS/idea"; else printf "%s\n" "4311 /bin/bash"; fi' > "$scratch/bin/ps"
printf '%s\n' '#!/bin/sh' 'printf "%s\n" "$*" >> "$KAST_TEST_KILL_LOG"' ': > "$KAST_TEST_IDEA_CLOSED"' > "$scratch/bin/kill"
printf '%s\n' '#!/bin/sh' 'printf "%s\n" "$*" >> "$KAST_TEST_BREW_LOG"' > "$scratch/bin/brew"
chmod 755 "$scratch/bin/uname" "$scratch/bin/curl" "$scratch/bin/unzip" "$scratch/bin/tar" "$scratch/bin/codex" "$scratch/bin/ps" "$scratch/bin/kill" "$scratch/bin/brew"

export PATH="$scratch/bin:$PATH"
export HOME="$scratch/home"
export KAST_RELEASES_URL="https://releases.test"
export KAST_TEST_CURL_LOG="$scratch/curl.log"
export KAST_TEST_SETUP_ARGS="$scratch/setup.args"
export KAST_TEST_CODEX_LOG="$scratch/codex.log"
export KAST_TEST_CONFIG_DEFAULTS="$scratch/config.toml"
export KAST_TEST_IDEA_CLOSED="$scratch/idea.closed"
export KAST_TEST_KILL_LOG="$scratch/kill.log"
export KAST_TEST_BREW_LOG="$scratch/brew.log"
export KAST_TEST_FZF_LOG="$scratch/fzf.log"
export KAST_TEST_FAKE_BIN="$scratch/bin"
unset NO_COLOR
export CLICOLOR_FORCE=1

bash "$repo_root/install.sh" --version v1.2.3 >"$scratch/stdout" 2>"$scratch/stderr"

grep -Fqx 'https://releases.test/download/v1.2.3/kast-v1.2.3-macos-arm64.zip' "$scratch/curl.log"
grep -Fqx 'https://releases.test/download/v1.2.3/kast-idea-v1.2.3.zip' "$scratch/curl.log"
grep -Eq '^setup --idea-plugin .*/kast-idea-v1\.2\.3\.zip$' "$scratch/setup.args"
grep -Fqx 'plugin marketplace add amichne/kast-marketplace --ref main --json' "$scratch/codex.log"
grep -Fqx 'plugin add kast@kast --json' "$scratch/codex.log"
grep -Fq $'\033[1;36m◆ KAST INSTALLER\033[0m' "$scratch/stderr"
grep -Fq $'\033[36m◆\033[0m Downloading Kast CLI' "$scratch/stderr"
grep -Fq $'\033[32m✓\033[0m Kast is ready' "$scratch/stderr"
grep -Fq "$HOME/.local/bin is not on PATH" "$scratch/stderr"
grep -Fq 'export PATH="$HOME/.local/bin:$PATH"' "$scratch/stderr"
if [[ -s "$scratch/stdout" ]]; then
  printf '%s\n' 'successful installer leaked the setup payload to stdout' >&2
  exit 1
fi
if grep -Fq 'type: KAST_SETUP' "$scratch/stderr"; then
  printf '%s\n' 'successful installer leaked the setup payload to stderr' >&2
  exit 1
fi
if grep -Fq 'kast-macos-arm64-v1.2.3.tar.gz' "$scratch/curl.log"; then
  printf '%s\n' 'macOS installer selected the headless bundle' >&2
  exit 1
fi

rm -f "$KAST_TEST_IDEA_CLOSED"
: > "$KAST_TEST_KILL_LOG"
KAST_TEST_IDEA_RUNNING=1 bash "$repo_root/install.sh" --version v1.2.3 --autostart \
  >"$scratch/stdout" 2>"$scratch/stderr" <<<"y"
grep -Fqx -- '-TERM 4312' "$KAST_TEST_KILL_LOG"
grep -Fq 'Detected IntelliJ IDEA (PID 4312)' "$scratch/stderr"
grep -Fq 'Close the detected editor and continue? [y/N]:' "$scratch/stderr"
grep -Fq 'enabled = true' "$KAST_TEST_CONFIG_DEFAULTS"
grep -Eq '^setup --idea-plugin .*/kast-idea-v1\.2\.3\.zip --config-defaults .*/config\.toml$' "$scratch/setup.args"

rm -f "$KAST_TEST_IDEA_CLOSED"
printf 'auto\nn\ny\nn\nn\nn\ny\ny\nn\n' | bash "$repo_root/install.sh" --version v1.2.3 --configure \
  >"$scratch/stdout" 2>"$scratch/stderr"
grep -Fqx 'defaultBackend = "auto"' "$KAST_TEST_CONFIG_DEFAULTS"
grep -Fqx 'strictPluginMatching = false' "$KAST_TEST_CONFIG_DEFAULTS"
grep -Fqx 'profileAutoInit = false' "$KAST_TEST_CONFIG_DEFAULTS"
grep -Fqx 'gradleLoadEnabled = false' "$KAST_TEST_CONFIG_DEFAULTS"
grep -Fqx 'autoExcludeGit = false' "$KAST_TEST_CONFIG_DEFAULTS"
grep -Fqx 'sessionStart = true' "$KAST_TEST_CONFIG_DEFAULTS"
grep -Fqx 'postToolUse = false' "$KAST_TEST_CONFIG_DEFAULTS"

: > "$scratch/curl.log"
KAST_TEST_OS=Linux KAST_TEST_ARCH=x86_64 bash "$repo_root/install.sh" --version v1.2.3 >"$scratch/stdout" 2>"$scratch/stderr"
grep -Fqx 'https://releases.test/download/v1.2.3/kast-linux-x64-v1.2.3.tar.gz' "$scratch/curl.log"

: > "$KAST_TEST_BREW_LOG"
: > "$KAST_TEST_FZF_LOG"
rm -f "$scratch/bin/fzf"
isolated_path="$scratch/bin:/usr/bin:/bin:/usr/sbin:/sbin"
KAST_TEST_INSTALLER="$repo_root/install.sh" KAST_TEST_PATH="$isolated_path" \
  KAST_TEST_SCREEN="$scratch/cancel.screen" expect <<'EXPECT' >/dev/null
set timeout 20
log_file -noappend $env(KAST_TEST_SCREEN)
spawn env PATH=$env(KAST_TEST_PATH) NO_COLOR= CLICOLOR_FORCE=1 KAST_TEST_FZF_LAST=1 bash $env(KAST_TEST_INSTALLER) --version v1.2.3
expect {
  -exact {Select [1]:} { send "cancel\r"; exp_continue }
  "Install fzf with Homebrew for interactive selection?" { send "y\r"; exp_continue }
  eof
}
catch wait result
exit [lindex $result 3]
EXPECT
if [[ -s "$KAST_TEST_BREW_LOG" ]]; then
  printf '%s\n' 'cancelled installer mutated Homebrew state' >&2
  exit 1
fi

: > "$KAST_TEST_BREW_LOG"
rm -f "$scratch/bin/fzf"
KAST_TEST_INSTALLER="$repo_root/install.sh" KAST_TEST_PATH="$isolated_path" \
  KAST_TEST_SCREEN="$scratch/interactive.screen" expect <<'EXPECT' >/dev/null
set timeout 20
log_file -noappend $env(KAST_TEST_SCREEN)
spawn env PATH=$env(KAST_TEST_PATH) NO_COLOR= CLICOLOR_FORCE=1 bash $env(KAST_TEST_INSTALLER) --version v1.2.3
expect -exact {Select [1]:}
send "configure\r"
expect -exact {Default backend (idea/auto) [idea]:}
send "\r"
expect -exact {Require matching Kast plugin version [Y/n]:}
send "\r"
expect -exact {Open new worktrees in a background IDEA instance [y/N]:}
send "\r"
expect -exact {Prepare Kast workspaces when projects open [Y/n]:}
send "\r"
expect -exact {Load the Gradle project model on open [Y/n]:}
send "\r"
expect -exact {Exclude managed setup files from Git [Y/n]:}
send "\r"
expect -exact {Enable Codex hooks [Y/n]:}
send "\r"
expect -exact {Open worktrees on Codex session start [Y/n]:}
send "\r"
expect -exact {Diagnose Kotlin files after writes [Y/n]:}
send "\r"
expect eof
catch wait result
exit [lindex $result 3]
EXPECT
if [[ -s "$KAST_TEST_BREW_LOG" ]]; then
  printf '%s\n' 'interactive installer mutated Homebrew state' >&2
  exit 1
fi
grep -Fq 'Default backend (idea/auto) [idea]:' "$scratch/interactive.screen"
grep -Fq '██╗  ██╗ █████╗ ███████╗████████╗' "$scratch/interactive.screen"
grep -Fq $'\033[33m?\033[0m' "$scratch/interactive.screen"

printf '%s\n' '#!/bin/sh' 'printf "%s\n" "$*" >> "$KAST_TEST_FZF_LOG"' 'if [ "${KAST_TEST_FZF_LAST:-}" = 1 ]; then tail -n 1; else sed -n "1p"; fi' > "$scratch/bin/fzf"
chmod 755 "$scratch/bin/fzf"
: > "$KAST_TEST_FZF_LOG"
KAST_TEST_INSTALLER="$repo_root/install.sh" KAST_TEST_PATH="$isolated_path" \
  KAST_TEST_SCREEN="$scratch/no-color.screen" expect <<'EXPECT' >/dev/null
set timeout 20
log_file -noappend $env(KAST_TEST_SCREEN)
spawn env PATH=$env(KAST_TEST_PATH) NO_COLOR=1 CLICOLOR_FORCE= bash $env(KAST_TEST_INSTALLER) --version v1.2.3 --configure
expect eof
catch wait result
exit [lindex $result 3]
EXPECT
grep -Fq -- '--no-color' "$KAST_TEST_FZF_LOG"
if LC_ALL=C grep -q $'\033' "$scratch/no-color.screen"; then
  printf '%s\n' 'NO_COLOR interactive output contains terminal color sequences' >&2
  exit 1
fi

: > "$KAST_TEST_BREW_LOG"
NO_COLOR=1 CLICOLOR_FORCE= PATH="$scratch/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
  bash "$repo_root/install.sh" --version v1.2.3 >"$scratch/stdout" 2>"$scratch/plain.stderr"
if LC_ALL=C grep -q $'\033' "$scratch/plain.stderr"; then
  printf '%s\n' 'redirected output contains terminal color sequences' >&2
  exit 1
fi
if grep -Fq 'install fzf' "$KAST_TEST_BREW_LOG"; then
  printf '%s\n' 'redirected install attempted to install fzf' >&2
  exit 1
fi
