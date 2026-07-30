#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
for script in pre-commit pre-push quality-gate.sh; do
  [[ -x "$repo_root/.githooks/$script" ]] || {
    printf 'missing executable Git quality-gate component: %s\n' "$script" >&2
    exit 1
  }
done
grep -Fq 'quality-gate.sh pre-commit' "$repo_root/.githooks/pre-commit"
grep -Fq 'quality-gate.sh pre-push' "$repo_root/.githooks/pre-push"

scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-quality-gate-test.XXXXXX")"
cleanup() {
  find "$scratch" -depth -delete
}
trap cleanup EXIT

fixture="$scratch/repository"
remote="$scratch/remote.git"
mkdir -p "$fixture/.github/scripts" "$fixture/.githooks" "$fixture/src"
cp "$repo_root/.gitattributes" "$fixture/.gitattributes"
cp "$repo_root/.github/scripts/check-repository-shape.py" "$fixture/.github/scripts/"
cp "$repo_root/.github/scripts/test-repository-shape-contract.sh" "$fixture/.github/scripts/"
cp "$repo_root/.githooks/quality-gate.sh" "$fixture/.githooks/"
git -C "$fixture" init --quiet
git -C "$fixture" config user.email "quality-gate@example.invalid"
git -C "$fixture" config user.name "Kast quality gate"
git init --quiet --bare "$remote"
git -C "$fixture" remote add origin "$remote"

formatter="$scratch/format.sh"
formatter_log="$scratch/formatter.log"
cat >"$formatter" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "-dry" ]]
shift
printf '%s\n' "$@" >>"${KAST_FORMATTER_LOG:?}"
for path in "$@"; do
  if grep -Fq 'BAD_FORMAT' "$path"; then
    exit 1
  fi
done
SH
chmod 755 "$formatter"
git -C "$fixture" config kast.ideaFormatter "$formatter"

printf 'class Sample\n' >"$fixture/src/Sample.kt"
git -C "$fixture" add .
git -C "$fixture" commit --quiet -m initial
git -C "$fixture" push --quiet origin HEAD:refs/heads/main

printf 'class SampleTwo\n' >"$fixture/src/Sample.kt"
git -C "$fixture" add src/Sample.kt
printf 'BAD_FORMAT\n' >"$fixture/src/Sample.kt"
KAST_FORMATTER_LOG="$formatter_log" "$fixture/.githooks/quality-gate.sh" pre-commit
grep -Fq 'Sample.kt' "$formatter_log"

git -C "$fixture" add src/Sample.kt
format_failure="$scratch/format-failure"
if KAST_FORMATTER_LOG="$formatter_log" \
  "$fixture/.githooks/quality-gate.sh" pre-commit >"$format_failure" 2>&1; then
  printf 'staged IDEA formatting difference did not block pre-commit\n' >&2
  exit 1
fi
grep -Fq 'Run the IntelliJ IDEA formatter without -dry' "$format_failure"

printf 'class SampleTwo\n' >"$fixture/src/Sample.kt"
git -C "$fixture" add src/Sample.kt
git -C "$fixture" commit --quiet -m formatted
printf 'BAD_FORMAT\n' >"$fixture/src/Sample.kt"
git -C "$fixture" add src/Sample.kt
git -C "$fixture" commit --quiet --no-verify -m unformatted
local_oid="$(git -C "$fixture" rev-parse HEAD)"
remote_oid="$(git -C "$fixture" rev-parse refs/remotes/origin/main)"
push_failure="$scratch/push-failure"
if printf 'refs/heads/main %s refs/heads/main %s\n' "$local_oid" "$remote_oid" |
  KAST_FORMATTER_LOG="$formatter_log" \
    "$fixture/.githooks/quality-gate.sh" pre-push origin "$remote" \
      >"$push_failure" 2>&1; then
  printf 'outgoing IDEA formatting difference did not block pre-push\n' >&2
  exit 1
fi
grep -Fq 'Run the IntelliJ IDEA formatter without -dry' "$push_failure"

git -C "$fixture" config kast.ideaFormatter "$scratch/missing-format.sh"
missing_failure="$scratch/missing-failure"
if "$fixture/.githooks/quality-gate.sh" pre-commit >"$missing_failure" 2>&1; then
  printf 'missing IDEA formatter did not block pre-commit\n' >&2
  exit 1
fi
grep -Fq 'git config kast.ideaFormatter' "$missing_failure"

printf '%s\n' 'Git quality gate contract passed'
