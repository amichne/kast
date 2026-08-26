#!/usr/bin/env python3
import json
import pathlib


def verify_kvp017_report(root):
    page = (root / "docs/engineering/ide-project-read-epoch.md").read_text()
    report = json.loads(
        (
            root
            / "workspace/intellij-read/src/test/resources/KVP-017-read-epoch.expected.json"
        ).read_text()
    )
    assert report["ideBuild"] == "262.9437.185"
    assert report["signalComponents"] == [
        "PROJECT_MODEL",
        "PSI",
        "ROOT_FILTERED_VFS",
        "ROOT_MODEL",
        "DUMB_MODE_TRACKER",
    ]
    assert report["comparisonRelations"] == ["SAME", "MOVED", "INCOMPARABLE"]
    assert len(report["observationFailures"]) == 27
    assert len(report["cases"]) == 13
    assert all(case["sampleCount"] == 2 for case in report["cases"])
    assert all(case["expectedRelation"] == case["observedRelation"] for case in report["cases"])
    assert {
        "maxVfsEventsPerBatch": 4_096,
        "maxVfsPathCharacters": 4_096,
        "maxVfsPathUtf8Bytes": 8_192,
        "maxCachedGradleModels": 16,
    }.items() <= report.items()
    for zero_field in (
        "primitiveCounterEscapeCount",
        "callerEpochReconstructionCount",
        "repeatedValidationCount",
        "dumbModeEpochValueCount",
        "vfsRefreshCount",
        "gradleImportCount",
        "gradleRepairCount",
        "repositoryWalkCount",
        "vfsTraversalCount",
        "sourceHashCount",
        "semanticJobCount",
        "edtSemanticWorkCount",
        "blockingWaitCount",
        "liveObjectEscapeCount",
    ):
        assert report[zero_field] == 0
    for expected_page_fact in (
        "262.9437.185",
        "PROJECT_MODEL",
        "PSI",
        "ROOT_FILTERED_VFS",
        "ROOT_MODEL",
        "DUMB_MODE_TRACKER",
        "`SAME`",
        "`MOVED`",
        "`INCOMPARABLE`",
        "4,096 events",
        "4,096 characters",
        "8,192 UTF-8 bytes",
        "16 cached Gradle models",
        "1,000-event VFS storm",
        "VFS traversals",
    ):
        assert expected_page_fact in page


if __name__ == "__main__":
    verify_kvp017_report(pathlib.Path(__file__).resolve().parents[1])
    print("KVP-017 report/page contract: valid")
