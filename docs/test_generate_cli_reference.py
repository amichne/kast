"""Public exposure owns documentation projection requirements."""
from pathlib import Path
import tempfile
import unittest

import generate_cli_reference as generator


class CliReferenceProjectionTest(unittest.TestCase):
    def test_internal_definitions_do_not_require_cli_source_projections(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            commands = root / 'cli/src/main/kotlin/io/github/amichne/kast/cli/command'
            commands.mkdir(parents=True)
            (commands / 'SymbolCommands.kt').write_text(
                'operation = CanonicalOperation.SYMBOL_DISCOVER,\n'
                'schemaUsage = "symbol discover < request.json", preparer = preparers.symbolDiscover')
            operations = [generator.OperationMetadata('INDEX_SYNC', 'index.sync', 'internal_only', ()),
                          generator.OperationMetadata('SYMBOL_DISCOVER', 'symbol.discover', 'public', ())]
            parsed = generator.parse_semantic_commands(root, operations)
            self.assertEqual(['symbol.discover'], [command.operation_id for command in parsed])

    def test_internal_source_declarations_are_optional_and_do_not_become_public_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            commands = root / 'cli/src/main/kotlin/io/github/amichne/kast/cli/command'
            commands.mkdir(parents=True)
            (commands / 'InternalCommands.kt').write_text(
                'operation = CanonicalOperation.INDEX_SYNC, '
                'schemaUsage = "index sync", preparer = preparers.indexSync')
            operations = [generator.OperationMetadata('INDEX_SYNC', 'index.sync', 'internal_only', ())]
            self.assertEqual([], generator.parse_semantic_commands(root, operations))

    def test_internal_and_unavailable_rows_come_directly_from_registry_metadata(self):
        operations = [generator.OperationMetadata('INDEX_SYNC', 'index.sync', 'internal_only', ()),
                      generator.OperationMetadata('TOPOLOGY_BUILD', 'topology.build', 'unavailable', ())]
        rendered = generator.render([], [], [], [], operations)
        self.assertIn('| `index.sync` | Acquired automatically as an internal sidecar service;', rendered)
        self.assertIn('| `topology.build` | Unavailable; no implementation binding,', rendered)
        self.assertNotIn('`kast index sync`', rendered)
        self.assertNotIn('`kast topology build`', rendered)

    def test_missing_public_projection_remains_a_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            operations = [generator.OperationMetadata('SYMBOL_DISCOVER', 'symbol.discover', 'public', ())]
            with self.assertRaisesRegex(ValueError, 'CLI projection mismatch'):
                generator.parse_semantic_commands(Path(directory), operations)


if __name__ == '__main__':
    unittest.main()
