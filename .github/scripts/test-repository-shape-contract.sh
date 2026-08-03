#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
checker="$repo_root/.github/scripts/check-repository-shape.py"
fixture="$(mktemp -d)"
external="$(mktemp)"
non_repository="$(mktemp -d)"
trap 'rm -rf -- "$fixture" "$non_repository"; rm -f -- "$external"' EXIT

git -C "$fixture" init --quiet
mkdir -p \
  "$fixture/line-limit" \
  "$fixture/child-limit" \
  "$fixture/non-source" \
  "$fixture/generated/overfull" \
  "$fixture/locked/overfull" \
  "$fixture/indexer"

printf 'plugins { id("kast.runtime-serialization-app") }\n' >"$fixture/indexer/build.gradle.kts"

for extension in kt kts rs; do
  for line in $(seq 1 400); do
    printf 'line %s\n' "$line"
  done >"$fixture/line-limit/exact.$extension"
done

for child in $(seq 1 10); do
  printf 'child %s\n' "$child" >"$fixture/child-limit/$child.txt"
done
for child in $(seq 1 11); do
  printf 'root boundary %s\n' "$child" >"$fixture/root-$child.txt"
done

for extension in md json py sh; do
  for line in $(seq 1 401); do
    printf 'non-source %s\n' "$line"
  done >"$fixture/non-source/large.$extension"
done
for child in $(seq 1 11); do
  printf 'generated child %s\n' "$child" >"$fixture/generated/overfull/$child.json"
  printf 'locked child %s\n' "$child" >"$fixture/locked/overfull/$child.txt"
done
for line in $(seq 1 401); do
  printf 'lock %s\n' "$line"
done >"$fixture/Cargo.lock"
for line in $(seq 1 401); do
  printf '\0binary %s\n' "$line"
done >"$fixture/binary.rs"
for line in $(seq 1 401); do
  printf 'external %s\n' "$line"
done >"$external"
ln -s "$external" "$fixture/external-link.rs"
printf '%s\n' \
  '/Cargo.lock repository-shape=lock' \
  '/generated/** repository-shape=generated' \
  '/locked/** repository-shape=lock' \
  >"$fixture/.gitattributes"
printf 'ignored.kt\n' >"$fixture/.gitignore"
for line in $(seq 1 401); do
  printf 'ignored %s\n' "$line"
done >"$fixture/ignored.kt"
git -C "$fixture" add .

for line in $(seq 1 401); do
  printf 'untracked %s\n' "$line"
done >"$fixture/untracked.rs"

baseline_output="$fixture/baseline-output.txt"
python3 "$checker" --root "$fixture" >"$baseline_output"
grep -Fq 'repositoryRoot: boundary' "$baseline_output"
grep -Fq 'files: trackedHandAuthoredKotlinAndRustSource' "$baseline_output"

operational_output="$fixture/operational-output.txt"
if python3 "$checker" --root "$non_repository" >"$operational_output" 2>&1; then
  printf '%s\n' 'expected a non-repository root to fail' >&2
  exit 1
fi
grep -Fq 'REPOSITORY_SHAPE_CHECK_FAILED' "$operational_output"
grep -Fq 'help:' "$operational_output"
if grep -Fq 'fatal:' "$operational_output"; then
  printf '%s\n' 'operational output leaked raw Git diagnostics' >&2
  exit 1
fi

cp "$fixture/.gitattributes" "$fixture/.gitattributes.valid"
printf '/line-limit/exact.kt repository-shape=unknown\n' >>"$fixture/.gitattributes"
invalid_output="$fixture/invalid-output.txt"
if python3 "$checker" --root "$fixture" >"$invalid_output"; then
  printf '%s\n' 'expected the unknown shape category to fail' >&2
  exit 1
fi
grep -Fq 'REPOSITORY_SHAPE_CHECK_FAILED' "$invalid_output"
mv "$fixture/.gitattributes.valid" "$fixture/.gitattributes"

for extension in kt kts rs; do
  printf 'line 401\n' >>"$fixture/line-limit/exact.$extension"
done
file_output="$fixture/file-output.txt"
if python3 "$checker" --root "$fixture" >"$file_output"; then
  printf '%s\n' 'expected the 401-line fixture to fail' >&2
  exit 1
fi
grep -Fq 'REPOSITORY_SHAPE_CONTRACT_VIOLATED' "$file_output"
for extension in kt kts rs; do
  grep -Fq "\"line-limit/exact.$extension\",401,400" "$file_output"
  head -n 400 "$fixture/line-limit/exact.$extension" >"$fixture/exact.$extension"
  mv "$fixture/exact.$extension" "$fixture/line-limit/exact.$extension"
done

printf 'child 11\n' >"$fixture/child-limit/11.txt"
git -C "$fixture" add "$fixture/child-limit/11.txt"
directory_output="$fixture/directory-output.txt"
if python3 "$checker" --root "$fixture" >"$directory_output"; then
  printf '%s\n' 'expected the 11-child fixture to fail' >&2
  exit 1
fi
grep -Fq '"child-limit",11,10' "$directory_output"

rm "$fixture/child-limit/11.txt"
git -C "$fixture" add -u "$fixture/child-limit/11.txt"

contract_root="$fixture/analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract"
for child in $(seq 1 10); do
  mkdir -p "$contract_root/child-$child"
  printf 'contract child %s\n' "$child" >"$contract_root/child-$child/Type.kt"
done
mkdir -p "$contract_root/transformation"
printf 'projection contract\n' >"$contract_root/transformation/Projection.kt"
git -C "$fixture" add "$fixture/analysis-api"
authorized_namespace_output="$fixture/authorized-namespace-output.txt"
if ! python3 "$checker" --root "$fixture" >"$authorized_namespace_output"; then
  grep -F '"analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract",11,10' "$authorized_namespace_output" >&2
  printf '%s\n' 'expected the authorized projection contract namespace to pass' >&2
  exit 1
fi

mkdir -p "$contract_root/unrelated-extra"
printf 'unrelated contract child\n' >"$contract_root/unrelated-extra/Type.kt"
git -C "$fixture" add "$contract_root/unrelated-extra/Type.kt"
unrelated_child_output="$fixture/unrelated-child-output.txt"
if python3 "$checker" --root "$fixture" >"$unrelated_child_output"; then
  printf '%s\n' 'expected an unrelated eleventh contract child to fail' >&2
  exit 1
fi
grep -Fq '"analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract",11,10' "$unrelated_child_output"

rm "$contract_root/unrelated-extra/Type.kt"
git -C "$fixture" add -u "$contract_root/unrelated-extra/Type.kt"
mkdir -p "$fixture/backend-idea"
printf 'retired\n' >"$fixture/backend-idea/build.gradle.kts"
printf 'run --backend idea\n' >"$fixture/legacy.md"
git -C "$fixture" add "$fixture/backend-idea/build.gradle.kts" "$fixture/legacy.md"
retired_output="$fixture/retired-output.txt"
if python3 "$checker" --root "$fixture" >"$retired_output"; then
  printf '%s\n' 'expected retired repository surfaces to fail' >&2
  exit 1
fi
grep -Fq '"backend-idea/build.gradle.kts","path:backend-idea/"' "$retired_output"
grep -Fq '"legacy.md","text:--backend"' "$retired_output"

rm "$fixture/backend-idea/build.gradle.kts" "$fixture/legacy.md" "$fixture/indexer/build.gradle.kts"
git -C "$fixture" add -u
required_output="$fixture/required-output.txt"
if python3 "$checker" --root "$fixture" >"$required_output"; then
  printf '%s\n' 'expected a missing indexer module to fail' >&2
  exit 1
fi
grep -Fq '"indexer/build.gradle.kts"' "$required_output"

printf '%s\n' 'repository shape contract passed'
