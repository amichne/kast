#!/usr/bin/env python3
"""Exact product schema selection and bounded native frame validation."""
from dataclasses import asdict, dataclass
from pathlib import Path
import hashlib
import tempfile
import unittest
from zipfile import ZipFile

from hosted_wire_schema import HostedWireSchemaFailure, HostedWireSchemaRejected, load_hosted_wire_schema


@dataclass(frozen=True)
class Rejected:
    failure: str = 'INVALID_REQUEST'
    type: str = 'HOST_REJECTED'


class HostedWireSchemaTest(unittest.TestCase):
    source = Path(__file__).resolve().parents[1] / 'protocol/contract/src/main/resources/ide-hosted/hosted-endpoint.schema.json'

    def jar(self, root, name='contract.jar', contents=None):
        (root / 'lib').mkdir(exist_ok=True)
        with ZipFile(root / 'lib' / name, 'w') as archive:
            archive.writestr('ide-hosted/hosted-endpoint.schema.json', self.source.read_bytes() if contents is None else contents)

    def test_exact_shipped_schema_digest_validates_known_and_rejects_unknown_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.jar(root)
            schema = load_hosted_wire_schema(root)
            self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(), schema.digest)
            self.assertTrue(schema.admits(asdict(Rejected())))
            self.assertFalse(schema.admits(asdict(Rejected('UNKNOWN'))))
            self.assertFalse(schema.admits({'type': 'HOST_REJECTED'}))

    def test_missing_duplicate_and_malformed_schemas_fail_closed(self):
        for mode, expected in (('missing', HostedWireSchemaFailure.MISSING),
                ('duplicate', HostedWireSchemaFailure.AMBIGUOUS), ('malformed', HostedWireSchemaFailure.INVALID)):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                if mode == 'duplicate':
                    self.jar(root); self.jar(root, 'duplicate.jar')
                elif mode == 'malformed':
                    self.jar(root, contents=b'{')
                with self.assertRaises(HostedWireSchemaRejected) as failure:
                    load_hosted_wire_schema(root)
                self.assertEqual(expected, failure.exception.failure)


if __name__ == '__main__':
    unittest.main()
