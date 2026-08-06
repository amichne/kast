#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'Pre-push check contract: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
hook="${repo_root}/.githooks/pre-push"
runner="${repo_root}/scripts/pre-push-check.sh"
installer="${repo_root}/scripts/install-git-hooks.sh"
workflow="${repo_root}/.github/workflows/ci.yml"

[[ -x "$hook" ]] || die 'missing executable .githooks/pre-push'
[[ -x "$runner" ]] || die 'missing executable scripts/pre-push-check.sh'
[[ -x "$installer" ]] || die 'missing executable scripts/install-git-hooks.sh'
python3 - "$workflow" <<'PY'
import re
import sys
from pathlib import Path

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
command = "bash .github/scripts/test-pre-push-check-contract.sh"
if workflow.count(command) != 1:
    raise SystemExit("CI must invoke the pre-push contract exactly once")
match = re.search(
    r"(?ms)^  workflow-contracts:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|\Z)",
    workflow,
)
if match is None or command not in match.group(1):
    raise SystemExit("the static workflow-contracts job must own the pre-push contract")
if "- name: Test pre-push check contract" not in match.group(1):
    raise SystemExit("the pre-push contract must have a named CI step")
PY

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-pre-push-contract.XXXXXX")"
trap 'rm -rf "$scratch_dir"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null
export GIT_CONFIG_NOSYSTEM=1
export PRE_PUSH_REAL_GIT="$(command -v git)"
fixture="${scratch_dir}/repository"
fake_bin="${scratch_dir}/bin"
command_log="${scratch_dir}/commands.log"
mkdir -p \
  "${fixture}/.githooks" \
  "${fixture}/.github/scripts/ci" \
  "${fixture}/cli-rs/src" \
  "${fixture}/scripts" \
  "$fake_bin"
cp "$hook" "${fixture}/.githooks/pre-push"
cp "$runner" "${fixture}/scripts/pre-push-check.sh"
cp "$installer" "${fixture}/scripts/install-git-hooks.sh"

cat >"${fixture}/.github/scripts/test-repository-shape-contract.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' 'shape-contract' >>"$PRE_PUSH_COMMAND_LOG"
[[ "${PRE_PUSH_FAIL_GATE:-}" != shape-contract ]]
SH
cat >"${fixture}/.github/scripts/ci/test-rust-agent-tooling-contract.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' 'rust-tooling-contract' >>"$PRE_PUSH_COMMAND_LOG"
[[ "${PRE_PUSH_FAIL_GATE:-}" != rust-tooling-contract ]]
SH
cat >"${fake_bin}/python3" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'python3' >>"$PRE_PUSH_COMMAND_LOG"
printf ' %s' "$@" >>"$PRE_PUSH_COMMAND_LOG"
printf '\n' >>"$PRE_PUSH_COMMAND_LOG"
[[ "${PRE_PUSH_FAIL_GATE:-}" != repository-shape-checker ]]
SH
cat >"${fake_bin}/cargo" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${PRE_PUSH_COMMAND_LOG:-}" ]]; then
  printf 'cargo' >>"$PRE_PUSH_COMMAND_LOG"
  printf ' %s' "$@" >>"$PRE_PUSH_COMMAND_LOG"
  printf '\n' >>"$PRE_PUSH_COMMAND_LOG"
fi
if [[ "${PRE_PUSH_MISSING_CARGO_DENY:-}" == true \
  && "${1:-}" == deny \
  && "${2:-}" == --version ]]; then
  exit 72
fi
if [[ "${PRE_PUSH_FAIL_GATE:-}" == "cargo-${1:-}" \
  && "${2:-}" != --version ]]; then
  exit 73
fi
SH
cat >"${fake_bin}/git" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${PRE_PUSH_FAIL_GIT_STATUS:-}" == true && "${1:-}" == status ]]; then
  exit 74
fi
exec "$PRE_PUSH_REAL_GIT" "$@"
SH
chmod +x \
  "${fixture}/.githooks/pre-push" \
  "${fixture}/.github/scripts/test-repository-shape-contract.sh" \
  "${fixture}/.github/scripts/ci/test-rust-agent-tooling-contract.sh" \
  "${fixture}/scripts/pre-push-check.sh" \
  "${fixture}/scripts/install-git-hooks.sh" \
  "${fake_bin}/cargo" \
  "${fake_bin}/git" \
  "${fake_bin}/python3"

git init -q "$fixture"
git -C "$fixture" config user.email 'pre-push-contract@example.invalid'
git -C "$fixture" config user.name 'Pre-push contract'
printf '%s\n' 'baseline' >"${fixture}/baseline.txt"
git -C "$fixture" add \
  .githooks/pre-push \
  .github/scripts/ci/test-rust-agent-tooling-contract.sh \
  .github/scripts/test-repository-shape-contract.sh \
  baseline.txt \
  scripts/install-git-hooks.sh \
  scripts/pre-push-check.sh
git -C "$fixture" commit -qm 'baseline'
remote_oid="$(git -C "$fixture" rev-parse HEAD)"
printf '%s\n' 'fn main() {}' >"${fixture}/cli-rs/src/main.rs"
git -C "$fixture" add cli-rs/src/main.rs
git -C "$fixture" commit -qm 'rust change'
local_oid="$(git -C "$fixture" rev-parse HEAD)"

PRE_PUSH_COMMAND_LOG="$command_log" \
PATH="${fake_bin}:${PATH}" \
  "${fixture}/.githooks/pre-push" origin example.invalid <<EOF
refs/heads/check ${local_oid} refs/heads/check ${remote_oid}
EOF

expected_log="${scratch_dir}/expected.log"
cat >"$expected_log" <<'EOF'
cargo deny --version
cargo fmt --version
cargo clippy --version
shape-contract
python3 .github/scripts/check-repository-shape.py --root .
rust-tooling-contract
cargo deny --manifest-path cli-rs/Cargo.toml --locked --all-features --config cli-rs/.config/deny.toml check
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
EOF
cmp -s "$expected_log" "$command_log" \
  || die "pre-push command order drifted: $(tr '\n' ';' <"$command_log")"

zero_oid="$(printf '%040d' 0)"
git -C "$fixture" update-ref refs/remotes/origin/main "$remote_oid"
: >"$command_log"
PRE_PUSH_COMMAND_LOG="$command_log" \
PATH="${fake_bin}:${PATH}" \
  "${fixture}/.githooks/pre-push" origin example.invalid <<EOF
refs/heads/new ${local_oid} refs/heads/new ${zero_oid}
EOF
cmp -s "$expected_log" "$command_log" \
  || die 'the pre-push hook did not validate every check for a new remote ref'

: >"$command_log"
PRE_PUSH_COMMAND_LOG="$command_log" \
PATH="${fake_bin}:${PATH}" \
  "${fixture}/.githooks/pre-push" origin example.invalid <<EOF
refs/heads/check ${zero_oid} refs/heads/check ${local_oid}
EOF
[[ ! -s "$command_log" ]] \
  || die 'the pre-push hook ran source checks for a deletion-only update'

: >"$command_log"
printf '%s\n' 'uncommitted' >"${fixture}/dirty.txt"
if PRE_PUSH_COMMAND_LOG="$command_log" \
  PATH="${fake_bin}:${PATH}" \
  "${fixture}/.githooks/pre-push" origin example.invalid <<EOF
refs/heads/check ${local_oid} refs/heads/check ${remote_oid}
EOF
then
  die 'the pre-push hook linted a dirty worktree'
fi
[[ ! -s "$command_log" ]] \
  || die 'the pre-push hook ran source checks against a dirty worktree'
rm "${fixture}/dirty.txt"

: >"$command_log"
if PRE_PUSH_COMMAND_LOG="$command_log" \
  PATH="${fake_bin}:${PATH}" \
  "${fixture}/.githooks/pre-push" origin example.invalid <<EOF
refs/heads/check ${remote_oid} refs/heads/check ${remote_oid}
EOF
then
  die 'the pre-push hook linted a commit other than current HEAD'
fi
[[ ! -s "$command_log" ]] \
  || die 'the pre-push hook ran source checks for a non-HEAD commit'

: >"$command_log"
if PRE_PUSH_COMMAND_LOG="$command_log" \
  PRE_PUSH_FAIL_GIT_STATUS=true \
  PATH="${fake_bin}:${PATH}" \
  "${fixture}/.githooks/pre-push" origin example.invalid <<EOF
refs/heads/check ${local_oid} refs/heads/check ${remote_oid}
EOF
then
  die 'the pre-push hook accepted a failed worktree inspection'
fi
[[ ! -s "$command_log" ]] \
  || die 'the pre-push hook ran source checks after worktree inspection failed'

for failing_gate in \
  shape-contract \
  repository-shape-checker \
  rust-tooling-contract \
  cargo-deny \
  cargo-fmt \
  cargo-clippy; do
  : >"$command_log"
  if PRE_PUSH_COMMAND_LOG="$command_log" \
    PRE_PUSH_FAIL_GATE="$failing_gate" \
    PATH="${fake_bin}:${PATH}" \
    "${fixture}/.githooks/pre-push" origin example.invalid <<EOF
refs/heads/check ${local_oid} refs/heads/check ${remote_oid}
EOF
  then
    die "the pre-push hook accepted a failed gate: ${failing_gate}"
  fi
done

printf 'trailing whitespace  \n' >"${fixture}/whitespace.txt"
git -C "$fixture" add whitespace.txt
git -C "$fixture" commit -qm 'invalid whitespace'
whitespace_oid="$(git -C "$fixture" rev-parse HEAD)"
: >"$command_log"
if PRE_PUSH_COMMAND_LOG="$command_log" \
  PATH="${fake_bin}:${PATH}" \
  "${fixture}/.githooks/pre-push" origin example.invalid <<EOF
refs/heads/check ${whitespace_oid} refs/heads/check ${local_oid}
EOF
then
  die 'the pre-push hook accepted whitespace errors in the pushed range'
fi
[[ ! -s "$command_log" ]] \
  || die 'the pre-push hook ran source checks after pushed whitespace failed'

PATH="${fake_bin}:${PATH}" \
  "${fixture}/scripts/install-git-hooks.sh" >/dev/null
expected_hooks_path="$(cd -- "${fixture}/.githooks" && pwd -P)"
configured_hooks_path="$(git -C "$fixture" config --worktree --get core.hooksPath)"
actual_hooks_path="$(cd -- "$configured_hooks_path" && pwd -P)"
[[ "$actual_hooks_path" == "$expected_hooks_path" ]] \
  || die "installer configured the wrong hook path: ${actual_hooks_path}"

linked_worktree="${scratch_dir}/linked-worktree"
git -C "$fixture" worktree add --detach -q "$linked_worktree" "$whitespace_oid"
PATH="${fake_bin}:${PATH}" \
  "${linked_worktree}/scripts/install-git-hooks.sh" >/dev/null
linked_configured_path="$(
  git -C "$linked_worktree" config --worktree --get core.hooksPath
)"
linked_expected_path="$(cd -- "${linked_worktree}/.githooks" && pwd -P)"
linked_actual_path="$(cd -- "$linked_configured_path" && pwd -P)"
[[ "$linked_actual_path" == "$linked_expected_path" ]] \
  || die "installer configured the wrong linked-worktree path: ${linked_actual_path}"
[[ "$(git -C "$fixture" config --worktree --get core.hooksPath)" == "$configured_hooks_path" ]] \
  || die 'linked-worktree installation changed the primary worktree hook path'

unmanaged="${scratch_dir}/unmanaged"
mkdir -p "${unmanaged}/scripts" "${unmanaged}/.githooks"
cp "$installer" "${unmanaged}/scripts/install-git-hooks.sh"
cp "$hook" "${unmanaged}/.githooks/pre-push"
git init -q "$unmanaged"
git -C "$unmanaged" config --local core.hooksPath custom-hooks
if "${unmanaged}/scripts/install-git-hooks.sh" >/dev/null 2>&1; then
  die 'installer replaced an unmanaged hooks path'
fi
[[ "$(git -C "$unmanaged" config --local --get core.hooksPath)" == custom-hooks ]] \
  || die 'installer changed the unmanaged hooks path before failing'

managed_lookalike="${scratch_dir}/managed-lookalike"
mkdir -p \
  "${managed_lookalike}/scripts" \
  "${managed_lookalike}/.githooks" \
  "${managed_lookalike}/custom-hooks"
cp "$installer" "${managed_lookalike}/scripts/install-git-hooks.sh"
cp "$hook" "${managed_lookalike}/.githooks/pre-push"
cp "$hook" "${managed_lookalike}/custom-hooks/pre-push"
cat >"${managed_lookalike}/custom-hooks/commit-msg" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod +x \
  "${managed_lookalike}/custom-hooks/pre-push" \
  "${managed_lookalike}/custom-hooks/commit-msg"
git init -q "$managed_lookalike"
git -C "$managed_lookalike" config --local \
  core.hooksPath "${managed_lookalike}/custom-hooks"
if PATH="${fake_bin}:${PATH}" \
  "${managed_lookalike}/scripts/install-git-hooks.sh" >/dev/null 2>&1; then
  die 'installer shadowed a sibling hook in a managed-looking directory'
fi
[[ "$(git -C "$managed_lookalike" config --local --get core.hooksPath)" \
  == "${managed_lookalike}/custom-hooks" ]] \
  || die 'installer changed the managed-looking hooks path before failing'

unmanaged_default="${scratch_dir}/unmanaged-default"
mkdir -p "${unmanaged_default}/scripts" "${unmanaged_default}/.githooks"
cp "$installer" "${unmanaged_default}/scripts/install-git-hooks.sh"
cp "$hook" "${unmanaged_default}/.githooks/pre-push"
git init -q "$unmanaged_default"
default_hook="$(
  git -C "$unmanaged_default" \
    rev-parse --path-format=absolute --git-path hooks/pre-push
)"
cat >"$default_hook" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod +x "$default_hook"
if "${unmanaged_default}/scripts/install-git-hooks.sh" >/dev/null 2>&1; then
  die 'installer shadowed an unmanaged default pre-push hook'
fi
[[ -x "$default_hook" ]] \
  || die 'installer changed the unmanaged default pre-push hook before failing'

missing_dependency="${scratch_dir}/missing-dependency"
mkdir -p "${missing_dependency}/scripts" "${missing_dependency}/.githooks"
cp "$installer" "${missing_dependency}/scripts/install-git-hooks.sh"
cp "$hook" "${missing_dependency}/.githooks/pre-push"
git init -q "$missing_dependency"
missing_dependency_error="${scratch_dir}/missing-dependency.stderr"
if PRE_PUSH_MISSING_CARGO_DENY=true \
  PATH="${fake_bin}:${PATH}" \
  "${missing_dependency}/scripts/install-git-hooks.sh" \
  >/dev/null 2>"$missing_dependency_error"; then
  die 'installer accepted a missing cargo-deny prerequisite'
fi
grep -Fq 'cargo install cargo-deny --locked' "$missing_dependency_error" \
  || die 'missing cargo-deny failed without exact remediation'
if git -C "$missing_dependency" config --get core.hooksPath >/dev/null; then
  die 'installer configured hooks before prerequisite verification completed'
fi

printf '%s\n' 'Pre-push check contract passed'
