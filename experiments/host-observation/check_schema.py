#!/usr/bin/env python3
"""Validate protocol examples and optional actual-host evidence (test-only jsonschema)."""
import argparse
import json
from pathlib import Path
import jsonschema
import controller as c
import test_controller as fixtures

SCHEMA = json.loads((c.ROOT / 'protocol.schema.json').read_text())

def validator(contract):
    return jsonschema.Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + contract}, format_checker=jsonschema.FormatChecker())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    jsonschema.Draft202012Validator.check_schema(SCHEMA)
    examples = [('Request', fixtures.request()), ('Receipt', fixtures.receipt()),
                ('Control', dict(type='STOP',version=1,sessionId=fixtures.RID)),
                ('Observation', dict(type='OBSERVATION',version=1,sessionId=fixtures.RID,sequence=1,event=dict(type='DUMB',transition='EXITED')))]
    for contract, value in examples:
        validator(contract).validate(value)
        changed = dict(value, version=2)
        assert not validator(contract).is_valid(changed)
        assert not validator(contract).is_valid(dict(value, extra='rejected'))
    checked = len(examples)
    if args.evidence:
        contracts = {'request.json':'Request','receipt.json':'Receipt','evaluation.json':'Evaluation','terminal.json':'Session'}
        for name, contract in contracts.items():
            for path in args.evidence.glob('*/' + name):
                validator(contract).validate(c.read_json(path, c.MAX_RECEIPT)); checked += 1
        for path in args.evidence.glob('*/receipt.json'):
            receipt = c.read_json(path, c.MAX_RECEIPT)
            if receipt['type'] != 'ATTACHED':
                continue
            directory = Path(receipt['sessionDirectory'])
            # Retention may already have evicted a proven retired session.
            if not directory.exists():
                continue
            validator('Session').validate(c.read_json(directory / 'status.json', c.MAX_RECEIPT)); checked += 1
            for segment in directory.glob('journal-*.jsonl'):
                for line in c.read_bytes(segment, 1024 * 1024).splitlines():
                    validator('Observation').validate(json.loads(line)); checked += 1
    print(f'protocol-schema: {checked} examples/records validated; invalid versions and extra fields rejected')


if __name__ == '__main__':
    main()
