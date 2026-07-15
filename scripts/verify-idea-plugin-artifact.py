#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from xml.etree import ElementTree


PLUGIN_ID = "io.github.amichne.kast"
IDEA_PLATFORM_ID = "idea"
SIGNATURE_VERIFICATION_TASKS = (
    ":backend-idea:verifyPluginStructure",
    ":backend-idea:verifyPluginXmlPresent",
    ":backend-idea:verifyPlugin",
    ":backend-idea:verifyPluginSignature",
)
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
GIT_SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
RELEASE_TAG_PATTERN = re.compile(r"v[0-9A-Za-z][0-9A-Za-z._-]*")


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


@dataclass(frozen=True)
class Sha256:
    value: str

    @classmethod
    def parse(cls, raw: object, *, field: str) -> "Sha256":
        if not isinstance(raw, str) or SHA256_PATTERN.fullmatch(raw) is None:
            fail(f"{field} must be 64 lowercase hexadecimal characters")
        return cls(raw)

    @classmethod
    def of_file(cls, path: Path) -> "Sha256":
        return cls(hashlib.sha256(path.read_bytes()).hexdigest())


@dataclass(frozen=True)
class EnrolledSigners:
    fingerprints: frozenset[Sha256]

    @classmethod
    def parse(cls, raw_fingerprints: list[str]) -> "EnrolledSigners":
        if not raw_fingerprints:
            fail("at least one --expected-signer-sha256 is required")
        fingerprints = frozenset(
            Sha256.parse(raw, field="expected signer fingerprint")
            for raw in raw_fingerprints
        )
        if len(fingerprints) != len(raw_fingerprints):
            fail("expected signer fingerprints must be unique")
        return cls(fingerprints)

    def require_enrolled(self, signer: Sha256) -> None:
        if signer not in self.fingerprints:
            fail(f"signer certificate is not enrolled: {signer.value}")


@dataclass(frozen=True)
class ReleaseIdentity:
    tag: str
    sha: str

    @classmethod
    def parse(cls, *, tag: object, sha: object) -> "ReleaseIdentity":
        if not isinstance(tag, str) or RELEASE_TAG_PATTERN.fullmatch(tag) is None:
            fail("release tag must start with v and contain only tag-safe characters")
        if not isinstance(sha, str) or GIT_SHA_PATTERN.fullmatch(sha) is None:
            fail("release SHA must be 40 lowercase hexadecimal characters")
        return cls(tag=tag, sha=sha)

    @classmethod
    def from_provenance(cls, raw: dict[str, Any]) -> "ReleaseIdentity":
        ref = raw.get("ref")
        if not isinstance(ref, str) or not ref.startswith("refs/tags/"):
            fail("IDEA plugin provenance ref must name a release tag")
        return cls.parse(tag=ref.removeprefix("refs/tags/"), sha=raw.get("sha"))

    @property
    def ref(self) -> str:
        return f"refs/tags/{self.tag}"


@dataclass(frozen=True)
class IdeaPluginProvenance:
    asset_name: str
    asset_digest: Sha256
    signer_certificate_sha256: Sha256
    release_identity: ReleaseIdentity

    @classmethod
    def parse(cls, raw: object) -> "IdeaPluginProvenance":
        if not isinstance(raw, dict):
            fail("IDEA plugin provenance must be a JSON object")
        payload: dict[str, Any] = raw
        if payload.get("platformId") != IDEA_PLATFORM_ID:
            fail("IDEA plugin provenance platformId must be idea")
        asset_name = payload.get("assetName")
        if not isinstance(asset_name, str) or not asset_name.endswith(".zip"):
            fail("IDEA plugin provenance assetName must name a ZIP")
        digest = payload.get("assetDigest")
        if not isinstance(digest, str) or not digest.startswith("sha256:"):
            fail("IDEA plugin provenance assetDigest must use sha256")
        if payload.get("pluginId") != PLUGIN_ID:
            fail(f"IDEA plugin provenance pluginId must be {PLUGIN_ID}")
        if payload.get("signatureVerified") is not True:
            fail("IDEA plugin provenance signatureVerified must be true")
        if payload.get("verificationTasks") != list(SIGNATURE_VERIFICATION_TASKS):
            fail("IDEA plugin provenance verificationTasks do not prove the signed compatibility gate")
        return cls(
            asset_name=asset_name,
            asset_digest=Sha256.parse(digest.removeprefix("sha256:"), field="assetDigest"),
            signer_certificate_sha256=Sha256.parse(
                payload.get("signerCertificateSha256"),
                field="signerCertificateSha256",
            ),
            release_identity=ReleaseIdentity.from_provenance(payload),
        )


def require_file(path: Path, *, description: str) -> None:
    if not path.is_file():
        fail(f"{description} does not exist: {path}")


def certificate_fingerprint(certificate_chain: Path) -> Sha256:
    require_file(certificate_chain, description="certificate chain")
    result = subprocess.run(
        ["openssl", "x509", "-in", str(certificate_chain), "-outform", "DER"],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        fail("certificate chain does not start with a valid X.509 certificate")
    return Sha256(hashlib.sha256(result.stdout).hexdigest())


def verify_signature(plugin_zip: Path, certificate_chain: Path, verifier_jar: Path) -> None:
    require_file(plugin_zip, description="plugin ZIP")
    require_file(certificate_chain, description="certificate chain")
    require_file(verifier_jar, description="Marketplace ZIP Signer CLI")
    try:
        result = subprocess.run(
            [
                "java",
                "-jar",
                str(verifier_jar),
                "verify",
                "-in",
                str(plugin_zip),
                "-cert",
                str(certificate_chain),
            ],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except FileNotFoundError:
        fail("java is required to execute the Marketplace ZIP Signer")
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip().splitlines()
        suffix = f": {detail[-1]}" if detail else ""
        fail(f"Marketplace ZIP Signer rejected the plugin archive{suffix}")


def plugin_id(plugin_zip: Path) -> str:
    require_file(plugin_zip, description="plugin ZIP")
    try:
        with zipfile.ZipFile(plugin_zip) as archive:
            descriptors = [
                archive.read(name)
                for name in sorted(archive.namelist())
                if name == "META-INF/plugin.xml" or name.endswith("/META-INF/plugin.xml")
            ]
            for jar_name in sorted(name for name in archive.namelist() if name.endswith(".jar")):
                with zipfile.ZipFile(io.BytesIO(archive.read(jar_name))) as jar:
                    if "META-INF/plugin.xml" in jar.namelist():
                        descriptors.append(jar.read("META-INF/plugin.xml"))
            if len(descriptors) != 1:
                fail(f"plugin ZIP must contain exactly one META-INF/plugin.xml, found {len(descriptors)}")
            descriptor = ElementTree.fromstring(descriptors[0])
    except (ElementTree.ParseError, KeyError, zipfile.BadZipFile) as error:
        fail(f"plugin ZIP has an invalid plugin descriptor: {error}")
    identifier = descriptor.findtext("id")
    if identifier != PLUGIN_ID:
        fail(f"plugin ZIP id must be {PLUGIN_ID}, got {identifier!r}")
    return identifier


def github_environment() -> dict[str, str]:
    required = (
        "GITHUB_RUN_ID",
        "GITHUB_RUN_NUMBER",
        "GITHUB_RUN_ATTEMPT",
        "GITHUB_WORKFLOW_REF",
        "GITHUB_ACTOR",
    )
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        fail(f"missing GitHub provenance environment: {missing}")
    return {name: os.environ[name] for name in required}


def record(args: argparse.Namespace) -> None:
    plugin_zip = Path(args.plugin_zip)
    certificate_chain = Path(args.certificate_chain)
    verifier_jar = Path(args.signature_verifier_jar)
    release_identity = ReleaseIdentity.parse(tag=args.release_tag, sha=args.release_sha)
    enrolled_signers = EnrolledSigners.parse(args.expected_signer_sha256)
    signer = certificate_fingerprint(certificate_chain)
    enrolled_signers.require_enrolled(signer)
    verify_signature(plugin_zip, certificate_chain, verifier_jar)
    identity = plugin_id(plugin_zip)
    digest = Sha256.of_file(plugin_zip)
    if args.asset_name != plugin_zip.name:
        fail("asset name must match the staged plugin ZIP filename")
    environment = github_environment()
    payload = {
        "runId": environment["GITHUB_RUN_ID"],
        "runNumber": environment["GITHUB_RUN_NUMBER"],
        "runAttempt": environment["GITHUB_RUN_ATTEMPT"],
        "sha": release_identity.sha,
        "ref": release_identity.ref,
        "workflowRef": environment["GITHUB_WORKFLOW_REF"],
        "actor": environment["GITHUB_ACTOR"],
        "platformId": IDEA_PLATFORM_ID,
        "assetName": args.asset_name,
        "assetDigest": f"sha256:{digest.value}",
        "pluginId": identity,
        "signerCertificateSha256": signer.value,
        "signatureVerified": True,
        "verificationTasks": list(SIGNATURE_VERIFICATION_TASKS),
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Recorded signed IDEA plugin provenance for {args.asset_name}")


def verify(args: argparse.Namespace) -> None:
    plugin_zip = Path(args.plugin_zip)
    certificate_chain = Path(args.certificate_chain)
    verifier_jar = Path(args.signature_verifier_jar)
    release_identity = ReleaseIdentity.parse(tag=args.release_tag, sha=args.release_sha)
    provenance_path = Path(args.provenance)
    require_file(provenance_path, description="IDEA plugin provenance")
    enrolled_signers = EnrolledSigners.parse(args.expected_signer_sha256)
    signer = certificate_fingerprint(certificate_chain)
    enrolled_signers.require_enrolled(signer)
    verify_signature(plugin_zip, certificate_chain, verifier_jar)
    plugin_id(plugin_zip)
    with provenance_path.open(encoding="utf-8") as handle:
        provenance = IdeaPluginProvenance.parse(json.load(handle))
    if provenance.asset_name != plugin_zip.name:
        fail(
            "IDEA plugin provenance assetName does not match plugin ZIP: "
            f"{provenance.asset_name} != {plugin_zip.name}"
        )
    digest = Sha256.of_file(plugin_zip)
    if provenance.asset_digest != digest:
        fail("IDEA plugin provenance digest does not match plugin ZIP bytes")
    if provenance.signer_certificate_sha256 != signer:
        fail("IDEA plugin provenance signer does not match the verified certificate chain")
    if provenance.release_identity != release_identity:
        fail("IDEA plugin provenance does not match the checked-out release tag and commit")
    print(f"Verified signed IDEA plugin artifact {plugin_zip.name} with signer {signer.value}")


def add_shared_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--plugin-zip", required=True)
    parser.add_argument("--certificate-chain", required=True)
    parser.add_argument("--signature-verifier-jar", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--release-sha", required=True)
    parser.add_argument(
        "--expected-signer-sha256",
        action="append",
        required=True,
        help="Enrolled certificate SHA-256. Repeat only for an explicit rotation overlap.",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Record or verify signer-bound provenance for a signed Kast IDEA plugin ZIP."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    record_parser = subparsers.add_parser("record")
    add_shared_arguments(record_parser)
    record_parser.add_argument("--output", required=True)
    record_parser.add_argument("--asset-name", required=True)
    record_parser.set_defaults(handler=record)
    verify_parser = subparsers.add_parser("verify")
    add_shared_arguments(verify_parser)
    verify_parser.add_argument("--provenance", required=True)
    verify_parser.set_defaults(handler=verify)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
