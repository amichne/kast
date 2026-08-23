#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-pr633-final-gate.XXXXXX")"
trap 'rm -rf "$fixture"' EXIT

git -C "$fixture" init -q
git -C "$fixture" config user.email test@example.invalid
git -C "$fixture" config user.name "Kast Test"
printf 'tracked\n' > "$fixture/tracked.txt"
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$@" > invocation.txt\n' > "$fixture/gradlew"
printf 'invocation.txt\n' > "$fixture/.gitignore"
chmod +x "$fixture/gradlew"
git -C "$fixture" add tracked.txt gradlew .gitignore
git -C "$fixture" commit -qm initial

KAST_PR633_REPOSITORY="$fixture" \
  "$repository_root/packaging/pr633-final-gate.sh" -Pproof=value
[[ "$(sed -n '1p' "$fixture/invocation.txt")" == "pr633MergeCandidateAcceptance" ]]
[[ "$(sed -n '2p' "$fixture/invocation.txt")" == "-Pproof=value" ]]

printf 'dirty\n' >> "$fixture/tracked.txt"
if KAST_PR633_REPOSITORY="$fixture" \
  "$repository_root/packaging/pr633-final-gate.sh" >/dev/null 2>&1; then
  echo "dirty checkout unexpectedly passed PR 633 final gate" >&2
  exit 1
fi
git -C "$fixture" restore tracked.txt
printf 'untracked\n' > "$fixture/untracked.txt"
if KAST_PR633_REPOSITORY="$fixture" \
  "$repository_root/packaging/pr633-final-gate.sh" >/dev/null 2>&1; then
  echo "untracked checkout unexpectedly passed PR 633 final gate" >&2
  exit 1
fi

echo "PR 633 final gate wrapper contract passed"
