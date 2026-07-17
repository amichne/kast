#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

compute_sha256() {
  local input_path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$input_path" | awk '{ print $1 }'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$input_path" | awk '{ print $1 }'
    return
  fi
  die "Neither sha256sum nor shasum is available"
}

write_zip_asset() {
  local asset_path="$1"
  local kind="$2"
  python3 - "$asset_path" "$kind" <<'PY'
import stat
import sys
import zipfile
import json
from pathlib import Path

asset_path = Path(sys.argv[1])
kind = sys.argv[2]

def write_entry(archive, name, data, mode=0o644):
    info = zipfile.ZipInfo(name)
    info.external_attr = (stat.S_IFREG | mode) << 16
    archive.writestr(info, data)

asset_path.parent.mkdir(parents=True, exist_ok=True)
if kind == "idea":
    with zipfile.ZipFile(asset_path, "w") as archive:
        write_entry(archive, "backend-idea/lib/backend-idea.jar", b"plugin")
elif kind == "codex":
    marketplace = {
        "name": "kast",
        "plugins": [{
            "name": "kast",
            "source": {"source": "local", "path": "./plugins/kast"},
            "policy": {"installation": "AVAILABLE", "authentication": "ON_INSTALL"},
            "category": "Productivity",
        }],
    }
    manifest = {
        "name": "kast",
        "version": "9.8.7",
        "description": "Kast Codex plugin fixture",
        "author": {"name": "Austin Michne"},
        "homepage": "https://kast.michne.com/",
        "repository": "https://github.com/amichne/kast",
        "license": "MIT",
        "skills": "./skills/",
        "interface": {
            "privacyPolicyURL": "https://kast.michne.com/privacy/",
            "termsOfServiceURL": "https://kast.michne.com/terms/",
        },
    }
    hook_events = {
        "SessionStart": "session-start",
        "SubagentStart": "subagent-start",
        "PreToolUse": "pre-tool-use",
        "PostToolUse": "post-tool-use",
        "Stop": "stop",
    }
    hooks = {
        "hooks": {
            codex_event: [{
                "hooks": [{
                    "type": "command",
                    "command": f'"$PLUGIN_ROOT/scripts/kast-codex-hook" {rust_event}',
                }]
            }]
            for codex_event, rust_event in hook_events.items()
        }
    }
    files = {
        "marketplace.json": json.dumps(marketplace).encode(),
        ".agents/plugins/marketplace.json": json.dumps(marketplace).encode(),
        "plugins/kast/.codex-plugin/plugin.json": json.dumps(manifest).encode(),
        "plugins/kast/hooks/hooks.json": json.dumps(hooks).encode(),
        "plugins/kast/scripts/kast-codex-hook": b"#!/bin/sh\nexit 0\n",
        "plugins/kast/skills/kast-codex/SKILL.md": b"---\nname: kast-codex\ndescription: \"Fixture skill.\"\n---\n\n# Kast Codex\n",
        "plugins/kast/skills/kast-codex/agents/openai.yaml": b"interface:\n  display_name: \"Kast\"\n  short_description: \"Kast fixture\"\n  default_prompt: \"Use $kast-codex.\"\n\npolicy:\n  allow_implicit_invocation: true\n",
        "plugins/kast/skills/kast-codex/references/commands.md": b"# Commands\n",
        "plugins/kast/skills/kast-codex/references/examples.md": b"# Examples\n",
        "plugins/kast/assets/codex-exposure.toon": b"version: 9.8.7\n",
        "plugins/kast/assets/hook-recovery-messages.toon": b"messages[0]:\n",
        "plugins/kast/assets/kast.svg": b"<svg/>\n",
    }
    with zipfile.ZipFile(asset_path, "w") as archive:
        for name, contents in files.items():
            write_entry(archive, name, contents, 0o755 if name.endswith("kast-codex-hook") else 0o644)
else:
    raise SystemExit(f"unknown asset kind: {kind}")
PY
}

write_text_asset() {
  local asset_path="$1"
  printf 'contents for %s\n' "$(basename -- "$asset_path")" > "$asset_path"
}

write_expected_assets() {
  write_text_asset "${release_dir}/kast-${tag}-linux-x64.zip"
  write_text_asset "${release_dir}/kast-${tag}-linux-arm64.zip"
  write_text_asset "${release_dir}/kast-${tag}-macos-x64.zip"
  write_text_asset "${release_dir}/kast-${tag}-macos-arm64.zip"
  write_zip_asset "${release_dir}/kast-codex-plugin-${tag}.zip" codex
  write_zip_asset "${release_dir}/kast-idea-${tag}.zip" idea
  write_text_asset "${release_dir}/kast-headless-linux-x64.tar.zst"
  python3 - "${release_dir}/kast-runtime-manifest.json" "${release_dir}/kast-headless-linux-x64.tar.zst" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
runtime_asset = Path(sys.argv[2])
payload = {
    "schemaVersion": 1,
    "kastVersion": "9.8.7",
    "kastGitSha": "0123456789abcdef",
    "os": "linux",
    "arch": "x64",
    "javaVersion": "21",
    "intellijBuild": "2025.3",
    "kotlinPluginVersion": "2.3.21",
    "kastIndexSchemaVersion": "7",
    "artifactSha256": hashlib.sha256(runtime_asset.read_bytes()).hexdigest(),
}
manifest_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
  rm -f "${release_dir}/kast-runtime-compatibility.json"
  "${repo_root}/.github/scripts/render-runtime-compatibility.py" render \
    --source "${repo_root}/packaging/jetbrains/runtime-compatibility.json" \
    --release-tag "$tag" \
    --release-sha "0123456789abcdef0123456789abcdef01234567" \
    --output "${release_dir}/kast-runtime-compatibility.json" \
    >/dev/null
  printf '%s  %s\n' \
    "$(compute_sha256 "${release_dir}/kast-headless-linux-x64.tar.zst")" \
    "kast-headless-linux-x64.tar.zst" \
    > "${release_dir}/kast-headless-linux-x64.sha256"
  write_text_asset "${release_dir}/gradle-ro-dep-cache.tar.zst"
  printf '%s  %s\n' \
    "$(compute_sha256 "${release_dir}/gradle-ro-dep-cache.tar.zst")" \
    "gradle-ro-dep-cache.tar.zst" \
    > "${release_dir}/gradle-ro-dep-cache.sha256"
  write_text_asset "${release_dir}/openapi.yaml"
  write_text_asset "${release_dir}/kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz"
  printf '%s  %s\n' \
    "$(compute_sha256 "${release_dir}/kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz")" \
    "kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz" \
    > "${release_dir}/kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz.sha256"
}

write_sha256sums() {
  local release_dir="$1"
  shift

  : > "${release_dir}/SHA256SUMS"
  local asset_name
  for asset_name in "$@"; do
    printf '%s  %s\n' "$(compute_sha256 "${release_dir}/${asset_name}")" "$asset_name" >> "${release_dir}/SHA256SUMS"
  done
}

write_provenance() {
  python3 - "$release_dir" "$tag" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

release_dir = Path(sys.argv[1])
tag = sys.argv[2]
entries = [
    ("cli-linux-x64", f"kast-{tag}-linux-x64.zip"),
    ("cli-linux-arm64", f"kast-{tag}-linux-arm64.zip"),
    ("cli-macos-x64", f"kast-{tag}-macos-x64.zip"),
    ("cli-macos-arm64", f"kast-{tag}-macos-arm64.zip"),
    ("codex-plugin", f"kast-codex-plugin-{tag}.zip"),
    ("gradle-ro-cache", "gradle-ro-dep-cache.tar.zst"),
    ("headless-linux-x64", "kast-headless-linux-x64.tar.zst"),
    ("openapi", "openapi.yaml"),
    ("runtime-manifest", "kast-runtime-manifest.json"),
    ("runtime-compatibility", "kast-runtime-compatibility.json"),
    ("ubuntu-debian-headless-x86_64", f"kast-ubuntu-debian-headless-x86_64-{tag}.tar.gz"),
    ("idea", f"kast-idea-{tag}.zip"),
]
builds = [
        {
            "platformId": platform,
            "assetName": asset,
            "assetDigest": "sha256:" + hashlib.sha256((release_dir / asset).read_bytes()).hexdigest(),
        }
        for platform, asset in entries
        if (release_dir / asset).is_file()
]
for entry in builds:
    if entry["platformId"] == "idea":
        entry.update({
            "pluginId": "io.github.amichne.kast",
            "signerCertificateSha256": "a" * 64,
            "signatureVerified": True,
            "sha": "0123456789abcdef0123456789abcdef01234567",
            "ref": f"refs/tags/{tag}",
            "verificationTasks": [
                ":backend-idea:verifyPluginStructure",
                ":backend-idea:verifyPluginXmlPresent",
                ":backend-idea:verifyPlugin",
                ":backend-idea:verifyPluginSignature",
            ],
        })
    if entry["platformId"] == "codex-plugin":
        entry.update({
            "sha": "0123456789abcdef0123456789abcdef01234567",
            "ref": f"refs/tags/{tag}",
            "pluginVersion": tag.removeprefix("v"),
            "generatorCommand": "kast developer codex generate --release",
        })
payload = {"builds": builds}
(release_dir / "build-provenance.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
}

repo_root="$(resolve_repo_root)"
verifier="${repo_root}/scripts/verify-release-assets.sh"
[[ -x "$verifier" ]] || die "release asset verifier is missing or not executable: $verifier"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-verify.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

tag="v9.8.7"
release_dir="${scratch_dir}/release"
mkdir -p "$release_dir"

assets=(
  "kast-${tag}-linux-x64.zip"
  "kast-${tag}-linux-arm64.zip"
  "kast-${tag}-macos-x64.zip"
  "kast-${tag}-macos-arm64.zip"
  "kast-codex-plugin-${tag}.zip"
  "gradle-ro-dep-cache.tar.zst"
  "kast-headless-linux-x64.tar.zst"
  "openapi.yaml"
  "kast-runtime-manifest.json"
  "kast-runtime-compatibility.json"
  "kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz"
  "kast-idea-${tag}.zip"
)

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance

"$verifier" --release-dir "$release_dir" --tag "$tag"

python3 - "${release_dir}/build-provenance.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
codex = next(entry for entry in payload["builds"] if entry.get("platformId") == "codex-plugin")
codex["pluginVersion"] = "9.8.8"
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
if "$verifier" --release-dir "$release_dir" --tag "$tag" \
  >"${scratch_dir}/codex-version.out" \
  2>"${scratch_dir}/codex-version.err"; then
  die "release with mismatched Codex plugin provenance unexpectedly verified"
fi
grep -Fq "Codex plugin provenance pluginVersion" "${scratch_dir}/codex-version.err" \
  || die "Codex plugin version failure did not name the provenance contract"
write_provenance

python3 - "${release_dir}/kast-runtime-compatibility.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["supportedPairs"][0]["requiredCapabilities"][0]["name"] = "UNKNOWN_CAPABILITY"
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
if "$verifier" --release-dir "$release_dir" --tag "$tag" \
  >"${scratch_dir}/compatibility-schema.out" \
  2>"${scratch_dir}/compatibility-schema.err"; then
  die "release with an unknown runtime capability unexpectedly verified"
fi
grep -Fq "known READ capability" "${scratch_dir}/compatibility-schema.err" \
  || die "invalid runtime compatibility failure did not name the capability contract"

rm -rf "$release_dir"
mkdir -p "$release_dir"
core_assets=(
  "kast-${tag}-linux-x64.zip"
  "kast-${tag}-linux-arm64.zip"
  "kast-${tag}-macos-x64.zip"
  "kast-${tag}-macos-arm64.zip"
  "kast-codex-plugin-${tag}.zip"
  "gradle-ro-dep-cache.tar.zst"
  "kast-headless-linux-x64.tar.zst"
  "openapi.yaml"
  "kast-runtime-manifest.json"
  "kast-runtime-compatibility.json"
  "kast-idea-${tag}.zip"
)
write_expected_assets
rm -f \
  "${release_dir}/kast-ubuntu-debian-headless_x86_64-${tag}.tar.gz" \
  "${release_dir}/kast-ubuntu-debian-headless_x86_64-${tag}.tar.gz.sha256" \
  "${release_dir}/kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz" \
  "${release_dir}/kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz.sha256"
write_sha256sums "$release_dir" "${core_assets[@]}"
write_provenance

if "$verifier" --release-dir "$release_dir" --tag "$tag" >"${scratch_dir}/missing-tarball.out" 2>"${scratch_dir}/missing-tarball.err"; then
  die "release without Linux headless tarball unexpectedly verified"
fi
grep -Fq "missing provenance" "${scratch_dir}/missing-tarball.err" \
  || die "missing Linux headless tarball failure did not mention missing provenance"

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
printf 'tampered\n' >> "${release_dir}/${assets[0]}"
if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/checksum.err"; then
  die "tampered asset unexpectedly verified"
fi
grep -Fq "checksum mismatch" "${scratch_dir}/checksum.err" || die "tampered asset failure did not mention checksum mismatch"

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
python3 - "${release_dir}/build-provenance.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["builds"] = [
    entry for entry in payload["builds"]
    if entry.get("platformId") != "idea"
]
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/provenance.err"; then
  die "missing provenance unexpectedly verified"
fi
grep -Fq "missing provenance" "${scratch_dir}/provenance.err" || die "missing provenance failure did not mention missing provenance"

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
python3 - "${release_dir}/build-provenance.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
idea = next(entry for entry in payload["builds"] if entry.get("platformId") == "idea")
idea["signatureVerified"] = False
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/signature.err"; then
  die "unsigned IDEA provenance unexpectedly verified"
fi
grep -Fq "signatureVerified" "${scratch_dir}/signature.err" \
  || die "unsigned IDEA provenance failure did not mention signature verification"

write_expected_assets
python3 - "${release_dir}/kast-runtime-manifest.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["kastVersion"] = "9.8.8"
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/manifest-version.err"; then
  die "wrong runtime manifest version unexpectedly verified"
fi
grep -Fq "kastVersion" "${scratch_dir}/manifest-version.err" || die "wrong manifest version failure did not mention kastVersion"

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
extra_asset="${release_dir}/kast-${tag}-debug.zip"
write_text_asset "$extra_asset"
printf '%s  %s\n' "$(compute_sha256 "$extra_asset")" "$(basename -- "$extra_asset")" >> "${release_dir}/SHA256SUMS"

if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/extra.err"; then
  die "extra release asset unexpectedly verified"
fi
grep -Fq "unexpected release asset" "${scratch_dir}/extra.err" || die "extra asset failure did not mention unexpected release asset"

printf '%s\n' "Release asset verifier test passed"
