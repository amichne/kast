#!/usr/bin/env python3
"""Write the bounded native pin carrier for an explicitly selected private IDEA."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--owned-root', type=Path, required=True)
parser.add_argument('--pid', type=int, required=True)
parser.add_argument('--phase', choices=['baseline', 'candidate'], default='candidate')
args = parser.parse_args()
root = args.owned_root.resolve()
assert args.pid > 0 and root != Path.home(), 'PRIVATE_NATIVE_IDENTITY_REQUIRED'
assert (root / 'manifest.json').is_file() and (root / 'fixture').is_dir(), 'FIXTURE_REQUIRED'
template = Path(__file__).with_name('pin.kts.in').read_text()
carrier = template.replace('"__OWNED_ROOT__"', json.dumps(str(root))).replace(
    '__OWNED_PID__', str(args.pid)).replace('__PHASE__', args.phase)
target = root / (args.phase + '-pin.kts')
assert not target.exists(), 'PIN_CARRIER_ALREADY_EXISTS'
target.write_text(carrier)
print(target)
