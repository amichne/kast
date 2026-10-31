"""The publication window depends on admitted main history, never job completion order."""

import json
import unittest
from dataclasses import replace

import developer_catalog as catalog


def entry(number: int) -> catalog.Build:
    return catalog.Build(
        mainRevision=f"{number:040x}",
        buildRevision="a" * 40,
        sourceTree="b" * 40,
        releaseTag=f"developer-v0.0.{number}",
        version=f"0.0.{number}",
        platformTargets=("macos-aarch64",),
        candidate=catalog.RecordReference("candidate.json", f"{number:064x}"),
        promotion=catalog.RecordReference("promotion.json", "d" * 64),
    )


class CatalogPolicyTest(unittest.TestCase):
    def test_seven_qualified_builds_arrive_out_of_order(self) -> None:
        history = tuple(f"{number:040x}" for number in range(9, 0, -1))
        window = catalog.Catalog(1, ())
        for number in (4, 2, 7, 1, 6, 3, 5):
            result = catalog.merge(window, entry(number), history)
            self.assertIsInstance(result, catalog.Catalog)
            assert isinstance(result, catalog.Catalog)
            window = result
        self.assertEqual(
            (7, 6, 5, 4, 3), tuple(int(build.mainRevision, 16) for build in window.builds)
        )
        self.assertEqual(window, catalog.merge(window, entry(1), history))
        # Main commits 8 and 9 have no admitted product and do not consume a slot.
        self.assertNotIn(f"{8:040x}", {build.mainRevision for build in window.builds})

    def test_republication_keeps_original_bytes_or_rejects(self) -> None:
        original = entry(3)
        current = catalog.Catalog(1, (original,))
        self.assertEqual(current, catalog.merge(current, original, (original.mainRevision,)))
        changed = replace(original, candidate=catalog.RecordReference("candidate.json", "e" * 64))
        self.assertEqual(
            catalog.Failure.IDENTITY_CONFLICT,
            catalog.merge(current, changed, (original.mainRevision,)),
        )

    def test_unproven_main_history_and_corrupt_order_reject(self) -> None:
        self.assertEqual(
            catalog.Failure.MAIN_HISTORY_UNPROVEN,
            catalog.merge(catalog.Catalog(1, ()), entry(2), (entry(1).mainRevision,)),
        )
        self.assertEqual(
            catalog.Failure.CATALOG_INVALID,
            catalog.merge(
                catalog.Catalog(1, (entry(1), entry(2))),
                entry(3),
                (entry(3).mainRevision, entry(2).mainRevision, entry(1).mainRevision),
            ),
        )

    def test_schema_preserves_both_revisions_and_record_digests(self) -> None:
        encoded = catalog.encode(catalog.Catalog(1, (entry(2),)))
        self.assertEqual({"schemaVersion", "builds"}, set(json.loads(encoded)))
        saved = json.loads(encoded)["builds"][0]
        self.assertEqual("a" * 40, saved["buildRevision"])
        self.assertEqual(f"{2:040x}", saved["mainRevision"])
        self.assertEqual({"name": "candidate.json", "sha256": f"{2:064x}"}, saved["candidate"])
        self.assertEqual(catalog.Catalog(1, (entry(2),)), catalog.decode(encoded))
        for raw in (
            encoded.replace('"schemaVersion":1', '"schemaVersion":true'),
            encoded.rstrip()[:-1],
            '{"schemaVersion":1,"schemaVersion":1,"builds":[]}',
            catalog.encode(catalog.Catalog(1, tuple(entry(number) for number in range(6, 0, -1)))),
        ):
            with self.subTest(raw=raw):
                self.assertEqual(catalog.Failure.CATALOG_INVALID, catalog.decode(raw))


class PublicationConcurrencyTest(unittest.TestCase):
    def test_contended_write_recomputes_from_newly_admitted_catalog(self) -> None:
        initial = catalog.Catalog(1, (entry(3),))
        concurrent = catalog.Catalog(1, (entry(5), entry(3)))
        snapshots = [
            catalog.Snapshot("a" * 40, "b" * 40, initial, False),
            catalog.Snapshot("c" * 40, "d" * 40, concurrent, False),
        ]
        writes: list[tuple[str, catalog.Catalog, str]] = []

        def read() -> catalog.Snapshot:
            return snapshots.pop(0)

        def history(revisions: tuple[str, ...]) -> tuple[str, ...]:
            return tuple(sorted(revisions, reverse=True))

        def commit(
            snapshot: catalog.Snapshot, document: catalog.Catalog, pointer: str
        ) -> catalog.CommitResult:
            writes.append((snapshot.head, document, pointer))
            return (
                catalog.CommitResult.CONTENDED
                if len(writes) == 1
                else catalog.CommitResult.COMMITTED
            )

        result = catalog.publish(entry(4), read, history, commit)
        self.assertEqual(catalog.CommitResult.COMMITTED, result)
        self.assertEqual((entry(5), entry(4), entry(3)), writes[1][1].builds)
        self.assertEqual("developer-v0.0.5 0.0.5 " + "a" * 40 + "\n", writes[1][2])
        self.assertFalse(snapshots)

    def test_continuous_contention_and_invalid_snapshot_never_report_success(self) -> None:
        reads = 0

        def read() -> catalog.Snapshot:
            nonlocal reads
            reads += 1
            return catalog.Snapshot("a" * 40, "b" * 40, catalog.Catalog(1, ()), False)

        def contend(
            snapshot: catalog.Snapshot, document: catalog.Catalog, pointer: str
        ) -> catalog.CommitResult:
            return catalog.CommitResult.CONTENDED

        result = catalog.publish(entry(1), read, lambda revisions: revisions, contend)
        self.assertEqual(catalog.Failure.CONCURRENT_CHANGE, result)
        self.assertEqual(catalog.MAXIMUM_PUBLICATION_TRIES, reads)


if __name__ == "__main__":
    unittest.main()
