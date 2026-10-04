"""Authored source-text intent. No compiler identity, live handle, or inferred flow is minted here."""
from dataclasses import asdict, dataclass
import argparse
from enum import Enum
import hashlib
import json
from pathlib import Path


class IntentFailure(str, Enum):
    OFFSET_UNIT_MISMATCH = 'OFFSET_UNIT_MISMATCH'
    ANCHOR_TEXT_MISMATCH = 'ANCHOR_TEXT_MISMATCH'
    IO_UNAVAILABLE = 'IO_UNAVAILABLE'
    OUTPUT_NOT_FRESH = 'OUTPUT_NOT_FRESH'


@dataclass(frozen=True)
class SourceTextVerified:
    sourceSha256: str
    type: str = 'SOURCE_TEXT_VERIFIED_REQUIRES_K2'


@dataclass(frozen=True)
class IntentRejected:
    failure: IntentFailure
    type: str = 'SOURCE_INTENT_REJECTED'


@dataclass(frozen=True)
class Anchor:
    name: str
    startInclusive: int
    endExclusive: int
    text: str
    intent: str


@dataclass(frozen=True)
class ModelIntent:
    rule: str
    namedCallable: str
    position: str
    meaning: str
    qualification: str = 'REQUIRES_REVIEWED_MODEL_AND_EXACT_K2_BINDING'


@dataclass(frozen=True)
class Manifest:
    sourcePath: str
    sourceSha256: str
    seedAnchors: tuple[Anchor, ...]
    namedUses: tuple[Anchor, ...]
    modelIntent: tuple[ModelIntent, ...]
    type: str = 'SOURCE_TEXT_INTENT'
    offsetUnit: str = 'UTF16_ASCII_FIXTURE'
    qualification: str = 'ACTUAL_K2_MUST_REVALIDATE_CALLABLE_SITE_POSITION_AND_BASIS'
    schemaVersion: int = 1


SEEDS = (
    Anchor('first-voltage', 876, 901, 'Voltage.encrypt(accountA)', 'Distinct producer invocation P1'),
    Anchor('second-voltage', 919, 944, 'Voltage.encrypt(accountB)', 'Distinct producer invocation P2'),
    Anchor('hiped-origin', 1296, 1319, 'Hiped.encrypt(accountA)', 'Bridge origin intent'),
    Anchor('voltage-bridge', 1385, 1411, 'Voltage.encrypt(plaintext)', 'Post-decryption producer intent'),
)
USES = (
    Anchor('misleading-name-consumer', 985, 1020, 'submit(misleadingHipedName, second)', 'P1 slot0 and P2 slot1 intent; variable names prove no representation'),
    Anchor('second-display', 1025, 1040, 'display(second)', 'P2 use intent'),
    Anchor('wrapper', 1059, 1073, 'wrapper(first)', 'Review required for compiler wrapper return support'),
    Anchor('wrapped-consumer', 1078, 1101, 'submit(wrapped, second)', 'Wrapped slot0 and separate P2 slot1 intent'),
    Anchor('first-assignment-read', 1133, 1152, 'display(reassigned)', 'Read before reassignment intent'),
    Anchor('second-assignment-read', 1181, 1200, 'display(reassigned)', 'Read after reassignment intent'),
    Anchor('branch-alternatives', 1205, 1253, 'val alternatives = if (choose) first else second', 'Alternative producers remain separate intent'),
    Anchor('hiped-decrypt', 1340, 1360, 'Hiped.decrypt(hiped)', 'HIPED to plaintext reviewed transformation intent'),
    Anchor('bridge-display', 1416, 1438, 'display(voltageBridge)', 'Current Voltage with retained HIPED origin history intent'),
    Anchor('unknown-transformation', 1457, 1473, 'unmodeled(first)', 'Unknown transformation cannot preserve known current representation'),
    Anchor('same-simple-name', 1515, 1539, 'Unrelated.decrypt(first)', 'Must not admit Hiped.decrypt model by simple name'),
    Anchor('response-constructor', 1582, 1597, 'Response(first)', 'Exact class/callable identity intent'),
    Anchor('response-send', 1602, 1616, 'send(response)', 'Boundary model and retention obligations require separate proof'),
    Anchor('unrelated-response', 1645, 1670, 'UnrelatedResponse(second)', 'Must not reuse Response identity'),
    Anchor('persistence-slot', 1717, 1742, 'persist("account", first)', 'Exact argument slot1 intent, retention obligations remain unresolved'),
)
MODELS = (
    ModelIntent('voltage-origin', 'representation.fixture.Voltage.encrypt', 'RESULT', 'Origin representation VOLTAGE'),
    ModelIntent('hiped-origin', 'representation.fixture.Hiped.encrypt', 'RESULT', 'Origin representation HIPED'),
    ModelIntent('hiped-decrypt', 'representation.fixture.Hiped.decrypt', 'ARGUMENT_0_TO_RESULT', 'HIPED to PLAINTEXT'),
    ModelIntent('voltage-encrypt', 'representation.fixture.Voltage.encrypt', 'ARGUMENT_0_TO_RESULT', 'PLAINTEXT to VOLTAGE'),
    ModelIntent('plaintext-consumer', 'representation.fixture.display', 'ARGUMENT_0', 'Expected PLAINTEXT is reviewed model data'),
)


def verify_text(source: Path) -> SourceTextVerified | IntentFailure:
    try:
        raw = source.read_bytes()
        text = raw.decode('ascii')
    except UnicodeError:
        return IntentFailure.OFFSET_UNIT_MISMATCH
    except OSError:
        return IntentFailure.IO_UNAVAILABLE
    if any(text[a.startInclusive:a.endExclusive] != a.text for a in (*SEEDS, *USES)):
        return IntentFailure.ANCHOR_TEXT_MISMATCH
    return SourceTextVerified(hashlib.sha256(raw).hexdigest())


def manifest(source: Path) -> Manifest | IntentFailure:
    admitted = verify_text(source)
    if isinstance(admitted, IntentFailure):
        return admitted
    return Manifest('logging/src/main/kotlin/representation/fixture/RepresentationImpactFixture.kt',
                    admitted.sourceSha256, SEEDS, USES, MODELS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = manifest(args.source)
    if isinstance(result, IntentFailure):
        print(json.dumps(asdict(IntentRejected(result))))
        return 2
    encoded = json.dumps(asdict(result), indent=2) + '\n'
    if args.output:
        try:
            with args.output.open('x') as stream:
                stream.write(encoded)
        except FileExistsError:
            print(json.dumps(asdict(IntentRejected(IntentFailure.OUTPUT_NOT_FRESH))))
            return 2
        except OSError:
            print(json.dumps(asdict(IntentRejected(IntentFailure.IO_UNAVAILABLE))))
            return 2
    else:
        print(encoded, end='')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
