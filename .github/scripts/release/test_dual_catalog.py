"""A dual release preserves its legacy view and advances both windows together."""

import json
import unittest
from dataclasses import asdict, replace

import developer_catalog as legacy
import dual_catalog as dual
import host_targets as hosts
from test_developer_catalog import entry


def targets(version: str) -> tuple[hosts.HostTarget, ...]:
    return tuple(
        hosts.HostTarget(
            line,
            f"{line}.123.45",
            f"{line}.123.45-IJ",
            legacy.RecordReference(hosts.record_name(version, line), line[0] * 64),
        )
        for line in ("262", "263")
    )


def paired(number: int) -> dual.PairedBuild:
    old = entry(number)
    current = dual.Build(
        old.mainRevision,
        old.buildRevision,
        old.sourceTree,
        old.releaseTag,
        old.version,
        old.platformTargets,
        legacy.RecordReference("candidate-v2.json", "e" * 64),
        legacy.RecordReference("promotion-v2.json", "f" * 64),
        targets(old.version),
    )
    return dual.PairedBuild(old, current)


class DualCatalogTest(unittest.TestCase):
    def test_strict_targets_reject_swaps_duplicates_and_wrong_sdk_lines(self) -> None:
        valid = targets("0.0.3")
        self.assertEqual(
            valid, hosts.decode_targets(json.loads(json.dumps(valid, default=asdict)), "0.0.3")
        )
        for invalid in (
            valid[::-1],
            (valid[0], valid[0]),
            (valid[0], replace(valid[1], ideBuild="262.123.45")),
            (valid[0], replace(valid[1], kotlinPluginBuild="262.123.45-IJ")),
            (valid[0], replace(valid[1], record=valid[0].record)),
            (valid[0], replace(valid[1], intellijReleaseLine="264")),
        ):
            with self.subTest(invalid=invalid):
                self.assertEqual(
                    hosts.Failure.INVALID_TARGETS,
                    hosts.decode_targets(json.loads(json.dumps(invalid, default=asdict)), "0.0.3"),
                )

    def test_v2_roundtrip_never_relabels_historical_legacy_entries(self) -> None:
        current = dual.Catalog(2, (paired(3).current,))
        self.assertEqual(current, dual.decode(dual.encode(current)))
        self.assertEqual(
            legacy.Failure.CATALOG_INVALID,
            dual.decode(legacy.encode(legacy.Catalog(1, (entry(2),)))),
        )
        self.assertEqual(
            legacy.Failure.CATALOG_INVALID,
            dual.decode(dual.encode(current).replace('"schemaVersion":2', '"schemaVersion":true')),
        )

    def test_paired_identity_drift_rejects_before_commit(self) -> None:
        value = paired(3)
        bad = replace(value, current=replace(value.current, sourceTree="e" * 40))
        result = dual.merge(
            dual.CatalogPair(legacy.Catalog(1, ()), dual.Catalog(2, ())),
            bad,
            (entry(3).mainRevision,),
        )
        self.assertEqual(legacy.Failure.IDENTITY_CONFLICT, result)

    def test_contention_reloads_both_views_and_preserves_legacy_history(self) -> None:
        first = dual.CatalogPair(legacy.Catalog(1, (entry(2),)), dual.Catalog(2, ()))
        newer = dual.CatalogPair(
            legacy.Catalog(1, (entry(5), entry(2))), dual.Catalog(2, (paired(5).current,))
        )
        snapshots = [
            dual.Snapshot("a" * 40, "b" * 40, first, False),
            dual.Snapshot("c" * 40, "d" * 40, newer, False),
        ]
        writes: list[tuple[str, dual.CatalogPair, str]] = []

        def commit(
            snapshot: dual.Snapshot, catalogs: dual.CatalogPair, pointer: str
        ) -> legacy.CommitResult:
            writes.append((snapshot.head, catalogs, pointer))
            return (
                legacy.CommitResult.CONTENDED if len(writes) == 1 else legacy.CommitResult.COMMITTED
            )

        result = dual.publish(
            paired(4),
            lambda: snapshots.pop(0),
            lambda revisions: tuple(sorted(revisions, reverse=True)),
            commit,
        )
        self.assertEqual(legacy.CommitResult.COMMITTED, result)
        self.assertEqual((entry(5), entry(4), entry(2)), writes[1][1].legacy.builds)
        self.assertEqual((paired(5).current, paired(4).current), writes[1][1].current.builds)
        self.assertEqual(legacy.latest_pointer(writes[1][1].legacy), writes[1][2])
        self.assertFalse(snapshots)

    def test_existing_cross_view_drift_cannot_be_laundered_by_new_build(self) -> None:
        current = dual.CatalogPair(
            legacy.Catalog(1, (entry(3),)),
            dual.Catalog(2, (replace(paired(3).current, sourceTree="e" * 40),)),
        )
        self.assertEqual(
            legacy.Failure.IDENTITY_CONFLICT,
            dual.merge(current, paired(4), (entry(4).mainRevision, entry(3).mainRevision)),
        )


if __name__ == "__main__":
    unittest.main()
