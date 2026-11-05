"""Remote publication verifies the union before one guarded three-file catalog write."""

import base64
import json
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path

import developer_catalog as catalog
import dual_candidate
import dual_catalog as dual
import publish_developer as publisher
import test_developer_publication as api
from test_dual_catalog import paired


class DualPublicationTest(unittest.TestCase):
    def test_three_discovery_files_use_one_tree_commit_and_guard(self) -> None:
        value = paired(3)
        documents = dual.CatalogPair(
            catalog.Catalog(1, (value.legacy,)), dual.Catalog(2, (value.current,))
        )
        snapshot = dual.Snapshot(
            "a" * 40, "b" * 40, dual.CatalogPair(catalog.Catalog(1, ()), dual.Catalog(2, ())), False
        )
        script = api.Script(
            api.Step("POST", "git/trees", api.response(api.Sha("c" * 40), 201)),
            api.Step("POST", "git/commits", api.response(api.Sha("d" * 40), 201)),
            api.Step(
                "PATCH", "git/refs/heads/developer-latest", api.response(api.Ref(api.Sha("d" * 40)))
            ),
        )
        remote = publisher.RemotePublisher(api.REPOSITORY, value.legacy, script.run)
        pointer = catalog.latest_pointer(documents.legacy)
        self.assertEqual(
            catalog.CommitResult.COMMITTED, remote.commit_dual(snapshot, documents, pointer)
        )
        expected = publisher.GitTreeRequest(
            "b" * 40,
            (
                publisher.GitTreeFile("builds.json", catalog.encode(documents.legacy)),
                publisher.GitTreeFile("builds-v2.json", dual.encode(documents.current)),
                publisher.GitTreeFile("latest.txt", pointer),
            ),
        )
        self.assertEqual(json.loads(json.dumps(asdict(expected))), script.requests[0][1])
        self.assertEqual(asdict(publisher.GitRefUpdate("d" * 40)), script.requests[2][1])
        script.consumed()

    def test_catalog_reads_share_captured_head_and_corruption_never_becomes_absence(self) -> None:
        value = paired(3)
        old = catalog.Catalog(1, (value.legacy,))
        for v2, expected in (
            (api.response(api.Contents(""), 404), dual.Catalog(2, ())),
            (
                api.response(
                    api.Contents(
                        base64.b64encode(
                            dual.encode(dual.Catalog(2, (value.current,))).encode()
                        ).decode()
                    )
                ),
                dual.Catalog(2, (value.current,)),
            ),
            (
                api.response(
                    api.Contents(base64.b64encode(b'{"schemaVersion":2,"builds":null}').decode())
                ),
                catalog.Failure.CATALOG_INVALID,
            ),
            (api.response(api.Contents(""), 403), catalog.Failure.REMOTE_UNAVAILABLE),
        ):
            script = api.Script(
                api.Step(
                    "GET",
                    "git/ref/heads/developer-latest",
                    api.response(api.Ref(api.Sha("a" * 40))),
                ),
                api.Step(
                    "GET",
                    "git/commits/" + "a" * 40,
                    api.response(api.Commit("a" * 40, api.Sha("b" * 40))),
                ),
                api.Step(
                    "GET",
                    "contents/builds.json?ref=" + "a" * 40,
                    api.response(
                        api.Contents(base64.b64encode(catalog.encode(old).encode()).decode())
                    ),
                ),
                api.Step("GET", "contents/builds-v2.json?ref=" + "a" * 40, v2),
            )
            remote = publisher.RemotePublisher(api.REPOSITORY, value.legacy, script.run)
            result = remote.snapshot_dual()
            if isinstance(expected, catalog.Failure):
                self.assertEqual(expected, result)
            else:
                self.assertEqual(
                    dual.Snapshot("a" * 40, "b" * 40, dual.CatalogPair(old, expected), False),
                    result,
                )
            script.consumed()

    def test_incomplete_remote_union_cannot_advance_either_catalog(self) -> None:
        value = paired(3)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            names = dual_candidate.names(value.legacy.version, True) | {
                "candidate.json",
                "candidate-v2.json",
                "promotion.json",
                "promotion-v2.json",
            }
            files: list[publisher.PublicationAsset] = []
            for name in sorted(names):
                path = root / name
                path.write_text(name)
                files.append(
                    publisher.PublicationAsset(path, publisher.digest(path), path.stat().st_size)
                )
            prepared = publisher.PreparedPublication(value.legacy, tuple(files), value.current)
            assets = tuple(
                api.RemoteAsset(file.source.name, "sha256:" + file.sha256, file.bytes)
                for file in files
            )
            self.assertEqual(28, len(assets))
            for wrong in (
                assets[:-1],
                (*assets, api.RemoteAsset("extra", "sha256:" + "e" * 64, 1)),
            ):
                script = api.Script(
                    api.Step(
                        "GET",
                        f"releases/tags/{value.legacy.releaseTag}",
                        api.response(
                            api.Release(value.legacy.releaseTag, value.legacy.buildRevision, wrong)
                        ),
                    )
                )
                remote = publisher.RemotePublisher(api.REPOSITORY, value.legacy, script.run)
                self.assertEqual(
                    publisher.Rejected(
                        publisher.Stage.IMMUTABLE_RELEASE, catalog.Failure.ASSET_MISMATCH
                    ),
                    publisher.publish(prepared, remote),
                )
                script.consumed()


if __name__ == "__main__":
    unittest.main()
