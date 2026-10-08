"""Independent original Konditional call-site intent for the owned tracked copy.

Positions are source inputs, not compiler findings. The copied-root native pin,
actual registered Pi receipts, and value-path checker remain separate gates.
"""

from dataclasses import asdict, replace
from hashlib import sha256
from pathlib import Path
import argparse
import json

import try_local_oracle as oracle


SOURCE = 'konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/IdentifierJsonAdapter.kt'
CALLEE = 'konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt'
TRY = '''try {
                FeatureId.parse(plainId)
            } catch (e: IllegalArgumentException) {
                throw JsonDataException("Invalid FeatureId '$plainId' at path ${reader.path}", e)
            }'''
BODY = '''{
                FeatureId.parse(plainId)
            }'''


def unique(text, fragment, file):
    if text.count(fragment) != 1:
        raise oracle.FixtureIntegrityError('Konditional authored fragment missing or duplicated')
    return replace(oracle.span(text, fragment), file=file)


def load_oracle(root):
    text = (root / SOURCE).read_text()
    callee = (root / CALLEE).read_text()
    declaration = unique(callee, 'fun parse(', CALLEE)
    parse_name = oracle.SourceSpan(CALLEE, declaration.startInclusive + 4, declaration.startInclusive + 9, 'parse')
    owner = unique(text, 'fun fromJson(', SOURCE)
    owner_name = oracle.SourceSpan(SOURCE, owner.startInclusive + 4, owner.startInclusive + 12, 'fromJson')
    producer = unique(text, 'FeatureId.parse(plainId)', SOURCE)
    enclosing_try = unique(text, TRY, SOURCE)
    rethrow = unique(text, 'JsonDataException("Invalid FeatureId \'$plainId\' at path ${reader.path}", e)', SOURCE)
    flow = oracle.FlowCase('konditional-feature-id-parse', owner_name, producer,
                           oracle.CompletionExpectation.NORMAL_RETURN, 1, 'TRY', (rethrow,),
                           (oracle.BranchResult(enclosing_try, unique(text, BODY, SOURCE), 'TRY'),),
                           (enclosing_try,))
    return oracle.SourceIntent(SOURCE, sha256(text.encode()).hexdigest(), (flow,), (),
                               (oracle.SourceInventory(CALLEE, sha256(callee.encode()).hexdigest()),),
                               scenario='konditional', sourceDirectory='konditional-json',
                               callables=(oracle.CallableIntent('FeatureId.', parse_name),))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(asdict(load_oracle(args.root)), indent=2))


if __name__ == '__main__':
    main()
