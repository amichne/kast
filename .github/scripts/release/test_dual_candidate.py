"""Retained candidate views share bytes but bind their own promotion digests."""

import json
import shutil
import unittest
from dataclasses import asdict, replace
from pathlib import Path

import build_candidate as legacy
import developer_catalog as catalog
import dual_candidate as dual
import host_targets as hosts
import test_build_candidate as fixtures
from test_build_candidate import BUILD, MERGED, VERSION


class DualCandidateTest(unittest.TestCase):
    def setUp(self) -> None:
        fixture = fixtures.PayloadAdmissionTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.bundle = fixture.bundle
        self.temporary = fixture.temporary
        self.value = fixture.value

    def dual_fixture(self) -> dual.Pair:
        payload = self.bundle / "payload"
        for name in (f"kast-ide-hosted-v{VERSION}-idea-263.zip", hosts.record_name(VERSION, "263")):
            (payload / name).write_bytes(name.encode())
            (payload / f"{name}.sha256").write_text(f"{dual.digest(payload / name)}  {name}\n")
        assets = tuple(
            legacy.Asset(path.name, dual.digest(path), path.stat().st_size)
            for path in sorted(payload.iterdir())
        )
        targets = tuple(
            hosts.HostTarget(
                line,
                f"{line}.123.45",
                f"{line}.123.45-IJ",
                catalog.RecordReference(
                    hosts.record_name(VERSION, line),
                    dual.digest(payload / hosts.record_name(VERSION, line)),
                ),
            )
            for line in ("262", "263")
        )
        old = self.value
        current = dual.Candidate(
            2,
            old.repository,
            old.sourceRevision,
            old.sourceTree,
            old.version,
            old.runId,
            old.runNumber,
            old.runAttempt,
            old.event,
            old.platform,
            old.toolchain,
            assets,
            targets,
        )
        (self.bundle / "candidate-v2.json").write_text(json.dumps(asdict(current)))
        return dual.Pair(old, current)

    def test_same_union_admits_with_exact_legacy_projection(self) -> None:
        pair = self.dual_fixture()
        self.assertEqual(pair, dual.read(self.bundle))
        projected = Path(self.temporary.name) / "old-reader"
        (projected / "payload").mkdir(parents=True)
        shutil.copyfile(self.bundle / "candidate.json", projected / "candidate.json")
        for asset in pair.legacy.assets:
            shutil.copyfile(
                self.bundle / "payload" / asset.name, projected / "payload" / asset.name
            )
        self.assertEqual(pair.legacy, legacy.read(projected))
        # The historical reader itself remains strict about the new union.
        with self.assertRaises(legacy.Rejected):
            legacy.read(self.bundle)

    def test_common_source_and_asset_drift_reject_even_when_each_record_decodes(self) -> None:
        pair = self.dual_fixture()
        for current in (
            replace(pair.current, sourceRevision=MERGED),
            replace(pair.current, runAttempt=2),
            replace(
                pair.current,
                assets=tuple(
                    replace(asset, bytes=asset.bytes + 1)
                    if asset.name == "host-installation.py"
                    else asset
                    for asset in pair.current.assets
                ),
            ),
        ):
            with self.subTest(current=current):
                self.assertEqual(
                    dual.Failure.IDENTITY_MISMATCH, dual.admit_pair(pair.legacy, current)
                )

    def test_target_record_digest_is_bound_to_manifest_asset(self) -> None:
        pair = self.dual_fixture()
        target = pair.current.hostTargets[1]
        changed = replace(target, record=replace(target.record, sha256="0" * 64))
        value = replace(pair.current, hostTargets=(pair.current.hostTargets[0], changed))
        self.assertEqual(dual.Failure.TARGET_MISMATCH, dual.decode(asdict(value)))

    def test_extra_and_missing_union_assets_reject(self) -> None:
        self.dual_fixture()
        extra = self.bundle / "payload" / "extra"
        extra.write_text("undeclared")
        self.assertEqual(dual.Failure.PAYLOAD_MISMATCH, dual.read(self.bundle))
        extra.unlink()
        (self.bundle / "payload" / hosts.record_name(VERSION, "263")).unlink()
        self.assertEqual(dual.Failure.PAYLOAD_MISMATCH, dual.read(self.bundle))

    def test_each_promotion_binds_its_own_record_bytes_and_same_main_identity(self) -> None:
        pair = self.dual_fixture()
        old_hash, new_hash = (
            dual.digest(self.bundle / "candidate.json"),
            dual.digest(self.bundle / "candidate-v2.json"),
        )
        old = legacy.Promotion(
            1,
            pair.legacy.repository,
            BUILD,
            MERGED,
            pair.legacy.sourceTree,
            VERSION,
            pair.legacy.runId,
            pair.legacy.runAttempt,
            456,
            1,
            old_hash,
        )
        current = replace(old, schemaVersion=2, candidateSha256=new_hash)
        self.assertEqual(current, dual.admit_promotions(pair, old, current, old_hash, new_hash))
        for invalid in (
            replace(current, candidateSha256=old_hash),
            replace(current, mainRunId=457),
            replace(current, mergedRevision=BUILD),
        ):
            self.assertEqual(
                dual.Failure.IDENTITY_MISMATCH,
                dual.admit_promotions(pair, old, invalid, old_hash, new_hash),
            )


if __name__ == "__main__":
    unittest.main()
