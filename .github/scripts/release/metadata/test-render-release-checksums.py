#!/usr/bin/env python3
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("render-release-checksums.py")
TAG = "v9.8.7"
OPENAPI_NAME = "openapi.yaml"
BUNDLE_NAME = f"kast-linux-x64-{TAG}.tar.gz"
OPENAPI_DIGEST = "a" * 64
BUNDLE_DIGEST = "b" * 64


class RenderReleaseChecksumsTest(unittest.TestCase):
    def run_renderer(
        self,
        *,
        bundle_state: str = "uploaded",
        sidecar_digest: str = BUNDLE_DIGEST,
        swap_platforms: bool = False,
        extra_product: str | None = None,
        bundle_name: str = BUNDLE_NAME,
    ) -> subprocess.CompletedProcess[str]:
        scratch = Path(self.temp_dir.name)
        sidecars = scratch / "sidecars"
        sidecars.mkdir(exist_ok=True)
        sidecar_name = f"{bundle_name}.sha256"
        sidecar = sidecars / sidecar_name
        sidecar.write_text(
            f"{sidecar_digest}  {bundle_name}\n",
            encoding="utf-8",
        )
        provenance = {
            "builds": [
                {
                    "platformId": "openapi" if swap_platforms else "setup-linux-x64",
                    "assetName": bundle_name,
                    "assetDigest": f"sha256:{BUNDLE_DIGEST}",
                },
                {
                    "platformId": "setup-linux-x64" if swap_platforms else "openapi",
                    "assetName": OPENAPI_NAME,
                    "assetDigest": f"sha256:{OPENAPI_DIGEST}",
                },
            ]
        }
        assets = {
            "assets": [
                {
                    "name": bundle_name,
                    "state": bundle_state,
                    "digest": f"sha256:{BUNDLE_DIGEST}",
                },
                {
                    "name": OPENAPI_NAME,
                    "state": "uploaded",
                    "digest": f"sha256:{OPENAPI_DIGEST}",
                },
                {
                    "name": sidecar_name,
                    "state": "uploaded",
                    "digest": "sha256:" + hashlib.sha256(sidecar.read_bytes()).hexdigest(),
                },
            ]
        }
        if extra_product is not None:
            assets["assets"].append(
                {
                    "name": extra_product,
                    "state": "uploaded",
                    "digest": f"sha256:{'e' * 64}",
                }
            )
        provenance_path = scratch / "provenance.json"
        assets_path = scratch / "assets.json"
        output = scratch / "SHA256SUMS"
        provenance_path.write_text(json.dumps(provenance), encoding="utf-8")
        assets_path.write_text(json.dumps(assets), encoding="utf-8")
        result = subprocess.run(
            [
                str(SCRIPT),
                "--tag",
                TAG,
                "--provenance",
                str(provenance_path),
                "--assets",
                str(assets_path),
                "--sidecars",
                str(sidecars),
                "--output",
                str(output),
            ],
            capture_output=True,
            text=True,
        )
        self.output = output
        return result

    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory(prefix="kast-release-checksums.")

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_renders_provenance_digests_without_release_asset_bytes(self) -> None:
        result = self.run_renderer()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            self.output.read_text(encoding="utf-8"),
            f"{BUNDLE_DIGEST}  {BUNDLE_NAME}\n{OPENAPI_DIGEST}  {OPENAPI_NAME}\n",
        )

    def test_rejects_incomplete_remote_asset(self) -> None:
        result = self.run_renderer(bundle_state="starter")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(f"release asset is not uploaded: {BUNDLE_NAME}", result.stderr)

    def test_rejects_sidecar_that_disagrees_with_provenance(self) -> None:
        result = self.run_renderer(sidecar_digest="c" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("checksum sidecar digest does not match provenance", result.stderr)

    def test_rejects_swapped_platform_assets(self) -> None:
        result = self.run_renderer(swap_platforms=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("provenance asset mismatch", result.stderr)

    def test_rejects_asset_from_another_tag(self) -> None:
        result = self.run_renderer(bundle_name="kast-linux-x64-v9.8.6.tar.gz")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("provenance asset mismatch", result.stderr)

    def test_rejects_unexpected_remote_product_asset(self) -> None:
        result = self.run_renderer(extra_product=f"kast-{TAG}-linux-x64.zip")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unexpected release product asset", result.stderr)


if __name__ == "__main__":
    unittest.main()
