"""Verify actual native serializer output using only the privately staged consumer."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", type=Path, required=True)
    parser.add_argument("--captures", type=Path, required=True)
    args = parser.parse_args()
    args.staged = args.staged.resolve(strict=True)
    args.captures = args.captures.resolve(strict=True)
    sys.path.insert(0, str(args.staged))
    import managed_release as consumer

    assert Path(consumer.__file__).resolve() == args.staged / "managed_release.py"
    versioned = consumer.decode_selection((args.captures / "v2.json").read_bytes())
    assert isinstance(versioned, consumer.MainSelection), versioned
    assert isinstance(versioned.catalogEntry, consumer.MainEntryV2)
    assert versioned.identity.mainRevision == "a" * 40
    assert versioned.identity.buildRevision == "b" * 40
    assert versioned.identity.sourceTree == "c" * 40
    assert versioned.catalogEntry.candidate.name == "candidate-v2.json"
    assert versioned.catalogEntry.promotion.name == "promotion-v2.json"
    profile = consumer.parse_supported_ide_targets((args.staged / "intellij-targets.json").read_bytes())
    assert isinstance(profile, consumer.SupportedIdeTargets), profile
    for line in ("262", "263"):
        target = consumer.select_host_target(versioned, line)
        assert isinstance(target, consumer.HostTarget), target
        assert target.intellijReleaseLine == line
        expected_sdk = next(sdk for sdk in profile.targets if sdk.intellijReleaseLine == line)
        assert target.ideBuild == expected_sdk.ideBuild
        assert target.kotlinPluginBuild == expected_sdk.kotlinPluginBuild
        assert target.record.sha256 == ("1" if line == "262" else "2") * 64
        suffix = "" if line == "262" else "-idea-263"
        assert target.record.name == f"kast-host-release-v0.0.5000{suffix}.json"
        assert target in versioned.catalogEntry.hostTargets
    legacy = consumer.decode_selection((args.captures / "v1.json").read_bytes())
    assert isinstance(legacy, consumer.MainSelection), legacy
    assert not isinstance(legacy.catalogEntry, consumer.MainEntryV2)
    assert legacy.identity.mainRevision == "d" * 40
    assert legacy.identity.buildRevision == "e" * 40
    assert isinstance(consumer.select_host_target(legacy, "262"), consumer.Legacy262Target)
    for selection in (legacy, consumer.decode_selection((args.captures / "stable.json").read_bytes())):
        assert not isinstance(selection, consumer.Rejected), selection
        rejected = consumer.select_host_target(selection, "263")
        assert isinstance(rejected, consumer.Rejected), rejected
        assert rejected.reason == consumer.Failure.HOST_TARGET_UNAVAILABLE
        assert rejected.stage == consumer.Stage.SELECTION
    print("SELECTION_CONTRACT_METADATA_FIXTURE: 5 target checks passed")


if __name__ == "__main__":
    main()
