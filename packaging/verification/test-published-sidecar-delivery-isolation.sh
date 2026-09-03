#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'published-sidecar-isolation-test: %s\n' "$*" >&2
  exit 1
}

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-published-isolation.XXXXXX")"
cleanup() {
  rm -rf -- "$fixture"
}
trap cleanup EXIT

subject_root="$fixture/repository"
mkdir -p "$subject_root/packaging/verification"
cp "$root/packaging/verification/verify-published-sidecar-delivery.sh" \
  "$subject_root/packaging/verification/verify-published-sidecar-delivery.sh"
cp "$root/packaging/verification/fixtures/published-installer-stub.sh" \
  "$subject_root/install.sh"
chmod +x "$subject_root/install.sh"

platform_home="$fixture/platform"
java_home="$fixture/java"
host_config="$fixture/host-config"
state="$fixture/state"
mkdir -p "$platform_home/plugins/Kotlin" "$java_home/bin" "$host_config/kast"
printf 'IU-262.9437.185\n' >"$platform_home/build.txt"
printf 'JAVA_VERSION="25.0.3"\nOS_ARCH="aarch64"\n' >"$java_home/release"
printf '#!/usr/bin/env bash\nexit 0\n' >"$java_home/bin/java"
chmod +x "$java_home/bin/java"
printf 'preserve-host-config\n' >"$host_config/kast/environment"

invalid_platform="$fixture/invalid-platform"
mkdir -p "$invalid_platform/plugins/Kotlin"
invalid_output="$fixture/invalid-output"
if env \
  XDG_CONFIG_HOME="$host_config" \
  bash "$subject_root/packaging/verification/verify-published-sidecar-delivery.sh" \
    --release v9.8.7 \
    --repository example/kast \
    --idea-platform-home "$invalid_platform" \
    --java-home "$java_home" >"$invalid_output" 2>&1; then
  fail "verifier admitted an IDEA platform without build identity"
fi
grep -Fq 'published-sidecar-delivery: IDEA platform build identity is absent' \
  "$invalid_output" || fail "invalid IDEA platform lost its finite failure"

output="$fixture/output"
if ! env \
  XDG_CONFIG_HOME="$host_config" \
  KAST_TEST_STATE="$state" \
  KAST_TEST_PLATFORM_HOME="$platform_home" \
  KAST_TEST_JAVA_HOME="$java_home" \
  KAST_TEST_KAST_STUB="$root/packaging/verification/fixtures/published-kast-stub.sh" \
  KAST_TEST_RELEASE_VERSION=9.8.7 \
  bash "$subject_root/packaging/verification/verify-published-sidecar-delivery.sh" \
    --release v9.8.7 \
    --repository example/kast \
    --idea-platform-home "$platform_home" \
    --java-home "$java_home" >"$output" 2>&1; then
  sed -n '1,120p' "$output" >&2
  fail "verifier did not admit explicit release-smoke IDEA inputs"
fi

[[ "$(<"$host_config/kast/environment")" == "preserve-host-config" ]] ||
  fail "verifier modified the host XDG configuration"
observed_home="$(sed -n '1p' "$state")"
observed_config="$(sed -n '2p' "$state")"
observed_idea_home="$(sed -n '3p' "$state")"
[[ -n "$observed_home" && -n "$observed_config" && -n "$observed_idea_home" ]] ||
  fail "installer observation is incomplete"
[[ "$(wc -l <"$state" | tr -d ' ')" == 3 ]] ||
  fail "installer observation contains unexpected records"
[[ "$observed_config" == "$observed_home/.config" ]] ||
  fail "installer configuration was not isolated under its temporary HOME"
[[ "$observed_idea_home" == "$observed_home/idea-home" ]] ||
  fail "installer did not receive the synthesized IDEA home"
grep -Fq 'published-sidecar-delivery: ok v9.8.7' "$output" ||
  fail "verifier did not publish its terminal success evidence"

printf 'published-sidecar-isolation-test: PASS\n'
