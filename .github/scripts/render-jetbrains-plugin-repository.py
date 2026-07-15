#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import SplitResult, urlsplit, urlunsplit
from xml.etree import ElementTree


SCHEMA_VERSION = 1
PLUGIN_ID = "io.github.amichne.kast"
IDEA_PLATFORM_ID = "idea"
RELEASE_ASSET_URL_TEMPLATE = (
    "https://github.com/amichne/kast/releases/download/{tag}/kast-idea-{tag}.zip"
)
RELEASE_TAG_PATTERN = re.compile(r"v[0-9A-Za-z][0-9A-Za-z._-]*")
VERSION_PATTERN = re.compile(r"[0-9A-Za-z][0-9A-Za-z._-]*")
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
GIT_SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
SINCE_BUILD_PATTERN = re.compile(r"[0-9]+(?:\.[0-9]+)*")
UNTIL_BUILD_PATTERN = re.compile(r"[0-9]+(?:\.[0-9]+)*(?:\.\*)?")
SIGNATURE_VERIFICATION_TASKS = (
    ":backend-idea:verifyPluginStructure",
    ":backend-idea:verifyPluginXmlPresent",
    ":backend-idea:verifyPlugin",
    ":backend-idea:verifyPluginSignature",
)


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def require_object(raw: object, *, field: str, keys: frozenset[str]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        fail(f"{field} must be a JSON object")
    payload: dict[str, Any] = raw
    actual_keys = frozenset(payload)
    if actual_keys != keys:
        fail(
            f"{field} keys must be exactly {sorted(keys)}; "
            f"missing={sorted(keys - actual_keys)} unexpected={sorted(actual_keys - keys)}"
        )
    return payload


def read_json(path: Path, *, description: str) -> object:
    if not path.is_file():
        fail(f"{description} does not exist: {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"{description} is not valid UTF-8 JSON: {error}")


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
        if not path.is_file():
            fail(f"plugin ZIP does not exist: {path}")
        return cls(hashlib.sha256(path.read_bytes()).hexdigest())


@dataclass(frozen=True)
class IdeaBuildRange:
    since_build: str
    until_build: str | None

    @classmethod
    def parse(cls, raw: object, *, field: str) -> "IdeaBuildRange":
        payload = require_object(
            raw,
            field=field,
            keys=frozenset(("sinceBuild", "untilBuild")),
        )
        since_build = payload["sinceBuild"]
        until_build = payload["untilBuild"]
        if not isinstance(since_build, str) or SINCE_BUILD_PATTERN.fullmatch(since_build) is None:
            fail(f"{field}.sinceBuild must be a numeric JetBrains build")
        if until_build is not None and (
            not isinstance(until_build, str)
            or UNTIL_BUILD_PATTERN.fullmatch(until_build) is None
        ):
            fail(f"{field}.untilBuild must be null or a numeric JetBrains build range")
        result = cls(since_build=since_build, until_build=until_build)
        result.require_ordered(field=field)
        return result

    @classmethod
    def from_descriptor(cls, element: ElementTree.Element) -> "IdeaBuildRange":
        allowed_attributes = frozenset(("since-build", "until-build"))
        actual_attributes = frozenset(element.attrib)
        if not actual_attributes <= allowed_attributes:
            fail(
                "plugin descriptor idea-version has unsupported attributes: "
                f"{sorted(actual_attributes - allowed_attributes)}"
            )
        return cls.parse(
            {
                "sinceBuild": element.attrib.get("since-build"),
                "untilBuild": element.attrib.get("until-build"),
            },
            field="plugin descriptor idea-version",
        )

    def require_ordered(self, *, field: str) -> None:
        if self.until_build is None:
            return
        since_parts = tuple(int(part) for part in self.since_build.split("."))
        wildcard = self.until_build.endswith(".*")
        raw_until = self.until_build.removesuffix(".*") if wildcard else self.until_build
        until_parts = tuple(int(part) for part in raw_until.split("."))
        if wildcard:
            compared_since = since_parts[: len(until_parts)]
            if until_parts < compared_since:
                fail(f"{field}.untilBuild is below sinceBuild")
            return
        width = max(len(since_parts), len(until_parts))
        padded_since = since_parts + (0,) * (width - len(since_parts))
        padded_until = until_parts + (0,) * (width - len(until_parts))
        if padded_until < padded_since:
            fail(f"{field}.untilBuild is below sinceBuild")

    def xml_attributes(self) -> dict[str, str]:
        attributes = {"since-build": self.since_build}
        if self.until_build is not None:
            attributes["until-build"] = self.until_build
        return attributes

    def manifest_value(self) -> dict[str, str | None]:
        return {
            "sinceBuild": self.since_build,
            "untilBuild": self.until_build,
        }


@dataclass(frozen=True)
class SigningPolicy:
    state: str
    active_signer: Sha256 | None
    next_signer: Sha256 | None

    @classmethod
    def parse(cls, raw: object) -> "SigningPolicy":
        payload = require_object(
            raw,
            field="signing",
            keys=frozenset(("activeSignerSha256", "rotation")),
        )
        rotation = require_object(
            payload["rotation"],
            field="signing.rotation",
            keys=frozenset(("state", "nextSignerSha256")),
        )
        state = rotation["state"]
        if state not in ("unconfigured", "stable", "overlap"):
            fail("signing.rotation.state must be unconfigured, stable, or overlap")
        active_raw = payload["activeSignerSha256"]
        next_raw = rotation["nextSignerSha256"]
        active = None if active_raw is None else Sha256.parse(
            active_raw,
            field="signing.activeSignerSha256",
        )
        next_signer = None if next_raw is None else Sha256.parse(
            next_raw,
            field="signing.rotation.nextSignerSha256",
        )
        if state == "unconfigured" and (active is not None or next_signer is not None):
            fail("unconfigured signing state must not name an active or rotation signer")
        if state == "stable" and (active is None or next_signer is not None):
            fail("stable signing state requires one active signer and no rotation signer")
        if state == "overlap" and (active is None or next_signer is None):
            fail("overlap signing state requires active and next signer fingerprints")
        if active is not None and active == next_signer:
            fail("rotation signer must differ from the active signer")
        return cls(state=state, active_signer=active, next_signer=next_signer)

    def enrolled_signers(self, *, require_configured: bool) -> tuple[Sha256, ...]:
        if self.state == "unconfigured":
            if require_configured:
                fail(
                    "production signing identity is unconfigured; add the enrolled public "
                    "certificate fingerprint to packaging/jetbrains/plugin-repository.json"
                )
            return ()
        if self.active_signer is None:
            fail("configured signing state has no active signer")
        if self.next_signer is None:
            return (self.active_signer,)
        return (self.active_signer, self.next_signer)


@dataclass(frozen=True)
class RepositoryPolicy:
    feed_url: str
    plugin_id: str
    asset_url_template: str
    idea_build_range: IdeaBuildRange

    @classmethod
    def parse(cls, raw: object) -> "RepositoryPolicy":
        payload = require_object(
            raw,
            field="repository",
            keys=frozenset(
                ("feedUrl", "pluginId", "releaseAssetUrlTemplate", "ideaBuildRange")
            ),
        )
        feed_url = payload["feedUrl"]
        plugin_id = payload["pluginId"]
        asset_template = payload["releaseAssetUrlTemplate"]
        if not isinstance(feed_url, str):
            fail("repository.feedUrl must be a string")
        require_https_url(feed_url, field="repository.feedUrl")
        if not urlsplit(feed_url).path.endswith("/jetbrains/updatePlugins.xml"):
            fail("repository.feedUrl must end with /jetbrains/updatePlugins.xml")
        if plugin_id != PLUGIN_ID:
            fail(f"repository.pluginId must be {PLUGIN_ID}")
        if not isinstance(asset_template, str):
            fail("repository.releaseAssetUrlTemplate must be a string")
        remaining_template = asset_template.replace("{tag}", "")
        if (
            asset_template.count("{tag}") != 2
            or "{" in remaining_template
            or "}" in remaining_template
        ):
            fail("repository.releaseAssetUrlTemplate must contain exactly two {tag} fields")
        sample_url = asset_template.replace("{tag}", "v1.2.3")
        parsed_sample = require_https_url(
            sample_url,
            field="repository.releaseAssetUrlTemplate",
        )
        if parsed_sample.hostname != "github.com":
            fail("repository.releaseAssetUrlTemplate must use github.com")
        if re.fullmatch(
            r"/[^/]+/[^/]+/releases/download/v1\.2\.3/kast-idea-v1\.2\.3\.zip",
            parsed_sample.path,
        ) is None:
            fail(
                "repository.releaseAssetUrlTemplate must name an immutable GitHub Release "
                "kast-idea-{tag}.zip asset"
            )
        if asset_template != RELEASE_ASSET_URL_TEMPLATE:
            fail(
                "repository.releaseAssetUrlTemplate must name the amichne/kast immutable "
                "release asset"
            )
        return cls(
            feed_url=feed_url,
            plugin_id=plugin_id,
            asset_url_template=asset_template,
            idea_build_range=IdeaBuildRange.parse(
                payload["ideaBuildRange"],
                field="repository.ideaBuildRange",
            ),
        )

    def asset_url(self, tag: str) -> str:
        require_release_tag(tag)
        return self.asset_url_template.replace("{tag}", tag)

    def asset_name(self, tag: str) -> str:
        return Path(urlsplit(self.asset_url(tag)).path).name


def require_https_url(raw: str, *, field: str) -> SplitResult:
    parsed = urlsplit(raw)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        fail(f"{field} must be an absolute HTTPS URL without credentials, query, or fragment")
    return parsed


@dataclass(frozen=True)
class RepositorySource:
    repository: RepositoryPolicy
    signing: SigningPolicy

    @classmethod
    def load(cls, path: Path) -> "RepositorySource":
        payload = require_object(
            read_json(path, description="JetBrains repository source"),
            field="JetBrains repository source",
            keys=frozenset(("schemaVersion", "repository", "signing")),
        )
        if payload["schemaVersion"] != SCHEMA_VERSION:
            fail(f"JetBrains repository source schemaVersion must be {SCHEMA_VERSION}")
        return cls(
            repository=RepositoryPolicy.parse(payload["repository"]),
            signing=SigningPolicy.parse(payload["signing"]),
        )


def manifest_url(source: RepositorySource) -> str:
    parsed = urlsplit(source.repository.feed_url)
    manifest_path = str(Path(parsed.path).with_name("plugin-repository-manifest.json"))
    return urlunsplit((parsed.scheme, parsed.netloc, manifest_path, "", ""))


def feed_url(source: RepositorySource) -> str:
    return source.repository.feed_url


def published_release_tag(*, source: RepositorySource, manifest_path: Path) -> str:
    payload = require_object(
        read_json(manifest_path, description="published JetBrains repository manifest"),
        field="published JetBrains repository manifest",
        keys=frozenset(("schemaVersion", "feedUrl", "releaseTag", "releaseSha", "entries")),
    )
    if payload["schemaVersion"] != SCHEMA_VERSION:
        fail(f"published JetBrains repository manifest schemaVersion must be {SCHEMA_VERSION}")
    if payload["feedUrl"] != source.repository.feed_url:
        fail("published JetBrains repository manifest feedUrl does not match source")
    release_tag = payload["releaseTag"]
    if not isinstance(release_tag, str):
        fail("published JetBrains repository manifest releaseTag must be a string")
    require_release_tag(release_tag)
    release_sha = payload["releaseSha"]
    if not isinstance(release_sha, str) or GIT_SHA_PATTERN.fullmatch(release_sha) is None:
        fail("published JetBrains repository manifest releaseSha must be a full Git SHA")
    entries = payload["entries"]
    if not isinstance(entries, list) or len(entries) != 1:
        fail("published JetBrains repository manifest must contain exactly one entry")
    entry = require_object(
        entries[0],
        field="published JetBrains repository manifest entry",
        keys=frozenset(
            (
                "pluginId",
                "version",
                "url",
                "sha256",
                "signerSha256",
                "ideaBuildRange",
            )
        ),
    )
    if entry["pluginId"] != source.repository.plugin_id:
        fail("published JetBrains repository manifest pluginId does not match source")
    if entry["version"] != release_tag.removeprefix("v"):
        fail("published JetBrains repository manifest version does not match releaseTag")
    if entry["url"] != source.repository.asset_url(release_tag):
        fail("published JetBrains repository manifest URL does not match source")
    Sha256.parse(entry["sha256"], field="published manifest entry sha256")
    signer = Sha256.parse(
        entry["signerSha256"],
        field="published manifest entry signerSha256",
    )
    if signer not in source.signing.enrolled_signers(require_configured=True):
        fail("published JetBrains repository manifest signer is not enrolled")
    published_range = IdeaBuildRange.parse(
        entry["ideaBuildRange"],
        field="published manifest entry ideaBuildRange",
    )
    if published_range != source.repository.idea_build_range:
        fail("published JetBrains repository manifest IDEA build range does not match source")
    return release_tag


@dataclass(frozen=True)
class PluginDescriptor:
    plugin_id: str
    version: str
    idea_build_range: IdeaBuildRange

    @classmethod
    def from_zip(cls, path: Path) -> "PluginDescriptor":
        if not path.is_file():
            fail(f"plugin ZIP does not exist: {path}")
        descriptors: list[bytes] = []
        try:
            with zipfile.ZipFile(path) as archive:
                for name in sorted(archive.namelist()):
                    if name == "META-INF/plugin.xml" or name.endswith("/META-INF/plugin.xml"):
                        descriptors.append(archive.read(name))
                for jar_name in sorted(name for name in archive.namelist() if name.endswith(".jar")):
                    with zipfile.ZipFile(io.BytesIO(archive.read(jar_name))) as jar:
                        if "META-INF/plugin.xml" in jar.namelist():
                            descriptors.append(jar.read("META-INF/plugin.xml"))
        except (KeyError, OSError, zipfile.BadZipFile) as error:
            fail(f"plugin ZIP is invalid: {error}")
        if len(descriptors) != 1:
            fail(f"plugin ZIP must contain exactly one plugin.xml, found {len(descriptors)}")
        try:
            root = ElementTree.fromstring(descriptors[0])
        except ElementTree.ParseError as error:
            fail(f"plugin descriptor is invalid XML: {error}")
        plugin_id = root.findtext("id")
        version = root.findtext("version")
        idea_versions = root.findall("idea-version")
        if plugin_id != PLUGIN_ID:
            fail(f"plugin descriptor id must be {PLUGIN_ID}")
        if not isinstance(version, str) or VERSION_PATTERN.fullmatch(version) is None:
            fail("plugin descriptor version must be a tag-safe version")
        if len(idea_versions) != 1:
            fail("plugin descriptor must contain exactly one idea-version element")
        return cls(
            plugin_id=plugin_id,
            version=version,
            idea_build_range=IdeaBuildRange.from_descriptor(idea_versions[0]),
        )


@dataclass(frozen=True)
class IdeaProvenance:
    release_tag: str
    release_sha: str
    asset_name: str
    asset_digest: Sha256
    plugin_id: str
    signer: Sha256
    raw_payload: dict[str, Any]

    @classmethod
    def load(cls, path: Path) -> "IdeaProvenance":
        raw = read_json(path, description="release provenance")
        if not isinstance(raw, dict):
            fail("release provenance must be a JSON object")
        payload: dict[str, Any] = raw
        if "builds" in payload:
            builds = payload["builds"]
            if not isinstance(builds, list) or not all(isinstance(entry, dict) for entry in builds):
                fail("combined release provenance builds must be a list of objects")
            platform_ids = [entry.get("platformId") for entry in builds]
            if not all(isinstance(platform_id, str) for platform_id in platform_ids):
                fail("combined release provenance platformId values must be strings")
            if len(set(platform_ids)) != len(platform_ids):
                fail("combined release provenance platformId values must be unique")
            if platform_ids != sorted(platform_ids, key=lambda value: str(value)):
                fail("combined release provenance builds must be ordered by platformId")
            idea_entries = [entry for entry in builds if entry.get("platformId") == IDEA_PLATFORM_ID]
            if len(idea_entries) != 1:
                fail("combined release provenance must contain exactly one IDEA entry")
            payload = idea_entries[0]
        if payload.get("platformId") != IDEA_PLATFORM_ID:
            fail("IDEA release provenance platformId must be idea")
        if payload.get("pluginId") != PLUGIN_ID:
            fail(f"IDEA release provenance pluginId must be {PLUGIN_ID}")
        if payload.get("signatureVerified") is not True:
            fail("IDEA release provenance signatureVerified must be true")
        if payload.get("verificationTasks") != list(SIGNATURE_VERIFICATION_TASKS):
            fail("IDEA release provenance does not carry the complete signed compatibility gate")
        ref = payload.get("ref")
        if not isinstance(ref, str) or not ref.startswith("refs/tags/"):
            fail("IDEA release provenance ref must name a release tag")
        release_tag = ref.removeprefix("refs/tags/")
        require_release_tag(release_tag)
        release_sha = payload.get("sha")
        if not isinstance(release_sha, str) or GIT_SHA_PATTERN.fullmatch(release_sha) is None:
            fail("IDEA release provenance sha must be a full lowercase Git SHA")
        asset_name = payload.get("assetName")
        if not isinstance(asset_name, str) or Path(asset_name).name != asset_name:
            fail("IDEA release provenance assetName must be a filename")
        asset_digest = payload.get("assetDigest")
        if not isinstance(asset_digest, str) or not asset_digest.startswith("sha256:"):
            fail("IDEA release provenance assetDigest must use sha256")
        return cls(
            release_tag=release_tag,
            release_sha=release_sha,
            asset_name=asset_name,
            asset_digest=Sha256.parse(
                asset_digest.removeprefix("sha256:"),
                field="IDEA release provenance assetDigest",
            ),
            plugin_id=PLUGIN_ID,
            signer=Sha256.parse(
                payload.get("signerCertificateSha256"),
                field="IDEA release provenance signerCertificateSha256",
            ),
            raw_payload=payload.copy(),
        )


def require_release_tag(tag: str) -> None:
    if RELEASE_TAG_PATTERN.fullmatch(tag) is None:
        fail("release tag must start with v and contain only tag-safe characters")


@dataclass(frozen=True)
class RepositoryEntry:
    plugin_id: str
    version: str
    url: str
    digest: Sha256
    signer: Sha256
    idea_build_range: IdeaBuildRange


def verified_entry(
    *,
    source: RepositorySource,
    plugin_zip: Path,
    provenance: IdeaProvenance,
) -> RepositoryEntry:
    enrolled_signers = source.signing.enrolled_signers(require_configured=True)
    if provenance.signer not in enrolled_signers:
        fail(f"IDEA release provenance signer is not enrolled: {provenance.signer.value}")
    digest = Sha256.of_file(plugin_zip)
    if provenance.asset_digest != digest:
        fail("IDEA release provenance digest does not match finalized plugin ZIP bytes")
    expected_asset_name = source.repository.asset_name(provenance.release_tag)
    if provenance.asset_name != expected_asset_name:
        fail("IDEA release provenance assetName does not match repository URL template")
    if plugin_zip.name != expected_asset_name:
        fail("finalized plugin ZIP filename does not match repository URL template")
    descriptor = PluginDescriptor.from_zip(plugin_zip)
    if descriptor.plugin_id != source.repository.plugin_id or provenance.plugin_id != descriptor.plugin_id:
        fail("plugin identity differs across source, provenance, and finalized ZIP")
    expected_version = provenance.release_tag.removeprefix("v")
    if descriptor.version != expected_version:
        fail("plugin descriptor version does not match finalized release tag")
    if descriptor.idea_build_range != source.repository.idea_build_range:
        fail("plugin descriptor IDEA build range does not match repository source")
    url = source.repository.asset_url(provenance.release_tag)
    if Path(urlsplit(url).path).name != provenance.asset_name:
        fail("rendered release URL does not name the finalized plugin asset")
    return RepositoryEntry(
        plugin_id=descriptor.plugin_id,
        version=descriptor.version,
        url=url,
        digest=digest,
        signer=provenance.signer,
        idea_build_range=descriptor.idea_build_range,
    )


def verify_finalized_signature(
    *,
    source: RepositorySource,
    plugin_zip: Path,
    provenance: IdeaProvenance,
    certificate_chain: Path,
    signature_verifier_jar: Path,
    artifact_verifier: Path,
) -> None:
    for path, description in (
        (certificate_chain, "public certificate chain"),
        (signature_verifier_jar, "Marketplace ZIP Signer CLI"),
        (artifact_verifier, "IDEA plugin artifact verifier"),
    ):
        if not path.is_file():
            fail(f"{description} does not exist: {path}")
    with tempfile.TemporaryDirectory(prefix="kast-idea-repository-") as temporary:
        direct_provenance = Path(temporary) / "idea-build-provenance.json"
        direct_provenance.write_text(
            json.dumps(provenance.raw_payload, indent=2) + "\n",
            encoding="utf-8",
        )
        command = [
            str(artifact_verifier),
            "verify",
            "--plugin-zip",
            str(plugin_zip),
            "--certificate-chain",
            str(certificate_chain),
            "--signature-verifier-jar",
            str(signature_verifier_jar),
            "--release-tag",
            provenance.release_tag,
            "--release-sha",
            provenance.release_sha,
            "--provenance",
            str(direct_provenance),
        ]
        for signer in source.signing.enrolled_signers(require_configured=True):
            command.extend(("--expected-signer-sha256", signer.value))
        try:
            result = subprocess.run(
                command,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
        except OSError as error:
            fail(f"IDEA plugin artifact verifier could not run: {error}")
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip().splitlines()
            suffix = f": {detail[-1]}" if detail else ""
            fail(f"finalized plugin ZIP failed cryptographic signature verification{suffix}")


def render_xml(entry: RepositoryEntry) -> bytes:
    root = ElementTree.Element("plugins")
    root.append(
        ElementTree.Comment(
            f" sha256={entry.digest.value}; signer-sha256={entry.signer.value} "
        )
    )
    plugin = ElementTree.SubElement(
        root,
        "plugin",
        {
            "id": entry.plugin_id,
            "url": entry.url,
            "version": entry.version,
        },
    )
    ElementTree.SubElement(plugin, "idea-version", entry.idea_build_range.xml_attributes())
    ElementTree.indent(root, space="  ")
    return ElementTree.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"


def verify_published_repository(
    *,
    source: RepositorySource,
    manifest_path: Path,
    xml_path: Path,
) -> str:
    release_tag = published_release_tag(source=source, manifest_path=manifest_path)
    raw = read_json(manifest_path, description="published JetBrains repository manifest")
    if not isinstance(raw, dict):
        fail("published JetBrains repository manifest must be a JSON object")
    raw_entries = raw.get("entries")
    if not isinstance(raw_entries, list) or len(raw_entries) != 1:
        fail("published JetBrains repository manifest must contain exactly one entry")
    raw_entry = raw_entries[0]
    if not isinstance(raw_entry, dict):
        fail("published JetBrains repository manifest entry must be a JSON object")
    entry = RepositoryEntry(
        plugin_id=str(raw_entry["pluginId"]),
        version=str(raw_entry["version"]),
        url=str(raw_entry["url"]),
        digest=Sha256.parse(raw_entry["sha256"], field="published manifest entry sha256"),
        signer=Sha256.parse(
            raw_entry["signerSha256"],
            field="published manifest entry signerSha256",
        ),
        idea_build_range=IdeaBuildRange.parse(
            raw_entry["ideaBuildRange"],
            field="published manifest entry ideaBuildRange",
        ),
    )
    if not xml_path.is_file():
        fail(f"published JetBrains repository XML does not exist: {xml_path}")
    try:
        actual_xml = xml_path.read_bytes()
    except OSError as error:
        fail(f"published JetBrains repository XML could not be read: {error}")
    expected_xml = render_xml(entry)
    if actual_xml != expected_xml:
        fail("published updatePlugins.xml does not match its validated manifest")
    return release_tag


def write_output(
    *,
    output_directory: Path,
    source: RepositorySource,
    provenance: IdeaProvenance,
    entry: RepositoryEntry,
) -> None:
    if output_directory.exists() and any(output_directory.iterdir()):
        fail(f"output directory must be absent or empty: {output_directory}")
    output_directory.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "feedUrl": source.repository.feed_url,
        "releaseTag": provenance.release_tag,
        "releaseSha": provenance.release_sha,
        "entries": [
            {
                "pluginId": entry.plugin_id,
                "version": entry.version,
                "url": entry.url,
                "sha256": entry.digest.value,
                "signerSha256": entry.signer.value,
                "ideaBuildRange": entry.idea_build_range.manifest_value(),
            }
        ],
    }
    xml_path = output_directory / "updatePlugins.xml"
    manifest_path = output_directory / "plugin-repository-manifest.json"
    xml_path.write_bytes(render_xml(entry))
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Rendered finalized JetBrains plugin repository for {provenance.release_tag}")


def validate_source_command(args: argparse.Namespace) -> None:
    source = RepositorySource.load(Path(args.source))
    source.signing.enrolled_signers(require_configured=args.require_configured)
    print(f"Validated JetBrains repository source ({source.signing.state})")


def signing_state_command(args: argparse.Namespace) -> None:
    print(RepositorySource.load(Path(args.source)).signing.state)


def enrolled_signers_command(args: argparse.Namespace) -> None:
    source = RepositorySource.load(Path(args.source))
    for signer in source.signing.enrolled_signers(
        require_configured=args.require_configured
    ):
        print(signer.value)


def asset_name_command(args: argparse.Namespace) -> None:
    source = RepositorySource.load(Path(args.source))
    print(source.repository.asset_name(args.tag))


def manifest_url_command(args: argparse.Namespace) -> None:
    print(manifest_url(RepositorySource.load(Path(args.source))))


def feed_url_command(args: argparse.Namespace) -> None:
    print(feed_url(RepositorySource.load(Path(args.source))))


def published_release_tag_command(args: argparse.Namespace) -> None:
    source = RepositorySource.load(Path(args.source))
    print(published_release_tag(source=source, manifest_path=Path(args.manifest)))


def provenance_release_sha_command(args: argparse.Namespace) -> None:
    print(IdeaProvenance.load(Path(args.provenance)).release_sha)


def verify_published_command(args: argparse.Namespace) -> None:
    source = RepositorySource.load(Path(args.source))
    print(
        verify_published_repository(
            source=source,
            manifest_path=Path(args.manifest),
            xml_path=Path(args.xml),
        )
    )


def render_command(args: argparse.Namespace) -> None:
    source = RepositorySource.load(Path(args.source))
    provenance = IdeaProvenance.load(Path(args.provenance))
    plugin_zip = Path(args.plugin_zip)
    verify_finalized_signature(
        source=source,
        plugin_zip=plugin_zip,
        provenance=provenance,
        certificate_chain=Path(args.certificate_chain),
        signature_verifier_jar=Path(args.signature_verifier_jar),
        artifact_verifier=Path(args.artifact_verifier),
    )
    entry = verified_entry(source=source, plugin_zip=plugin_zip, provenance=provenance)
    write_output(
        output_directory=Path(args.output_directory),
        source=source,
        provenance=provenance,
        entry=entry,
    )


def add_source_argument(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--source", required=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate and render Kast's signed custom JetBrains plugin repository."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate-source")
    add_source_argument(validate_parser)
    validate_parser.add_argument("--require-configured", action="store_true")
    validate_parser.set_defaults(handler=validate_source_command)

    state_parser = subparsers.add_parser("signing-state")
    add_source_argument(state_parser)
    state_parser.set_defaults(handler=signing_state_command)

    signers_parser = subparsers.add_parser("enrolled-signers")
    add_source_argument(signers_parser)
    signers_parser.add_argument("--require-configured", action="store_true")
    signers_parser.set_defaults(handler=enrolled_signers_command)

    asset_parser = subparsers.add_parser("asset-name")
    add_source_argument(asset_parser)
    asset_parser.add_argument("--tag", required=True)
    asset_parser.set_defaults(handler=asset_name_command)

    manifest_url_parser = subparsers.add_parser("manifest-url")
    add_source_argument(manifest_url_parser)
    manifest_url_parser.set_defaults(handler=manifest_url_command)

    feed_url_parser = subparsers.add_parser("feed-url")
    add_source_argument(feed_url_parser)
    feed_url_parser.set_defaults(handler=feed_url_command)

    published_tag_parser = subparsers.add_parser("published-release-tag")
    add_source_argument(published_tag_parser)
    published_tag_parser.add_argument("--manifest", required=True)
    published_tag_parser.set_defaults(handler=published_release_tag_command)

    provenance_sha_parser = subparsers.add_parser("provenance-release-sha")
    provenance_sha_parser.add_argument("--provenance", required=True)
    provenance_sha_parser.set_defaults(handler=provenance_release_sha_command)

    verify_published_parser = subparsers.add_parser("verify-published")
    add_source_argument(verify_published_parser)
    verify_published_parser.add_argument("--manifest", required=True)
    verify_published_parser.add_argument("--xml", required=True)
    verify_published_parser.set_defaults(handler=verify_published_command)

    render_parser = subparsers.add_parser("render")
    add_source_argument(render_parser)
    render_parser.add_argument("--plugin-zip", required=True)
    render_parser.add_argument("--provenance", required=True)
    render_parser.add_argument("--certificate-chain", required=True)
    render_parser.add_argument("--signature-verifier-jar", required=True)
    render_parser.add_argument(
        "--artifact-verifier",
        default=str(
            Path(__file__).resolve().parents[2]
            / "scripts"
            / "verify-idea-plugin-artifact.py"
        ),
    )
    render_parser.add_argument("--output-directory", required=True)
    render_parser.set_defaults(handler=render_command)

    return parser.parse_args()


def main() -> None:
    args = parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
