#!/usr/bin/env python3
"""One required expensive gate, with explicit input and resource admission."""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
from acceptance_idea import BUILD, SHA256, URL, admit_home, digest, provision


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--product', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--idea-cache', type=Path, required=True)
    parser.add_argument('--harness-classpath-file', type=Path, required=True)
    parser.add_argument('--profile', choices=('local-8g', 'ci-small'), default='local-8g')
    args = parser.parse_args()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    try:
        if platform.system() != 'Darwin' or platform.machine() != 'arm64':
            raise ValueError('ACCEPTANCE_PLATFORM_REJECTED')
        memory = int(subprocess.check_output(['/usr/sbin/sysctl', '-n', 'hw.memsize'], text=True))
        minimum_gib = 32 if args.profile == 'local-8g' else 14
        if memory < minimum_gib * 1024 ** 3:
            raise ValueError(f'ACCEPTANCE_MEMORY_REJECTED: {args.profile} requires {minimum_gib} GiB including build overhead')
        # Generated runtime metadata is the product authority. Never substitute a
        # different IDE build to satisfy an unavailable download.
        metadata = json.loads((args.product / 'share/kast/semantic-runtime.json').read_text())
        if metadata.get('ideaBuild') != BUILD:
            raise ValueError('ACCEPTANCE_PRODUCT_IDEA_BUILD_REJECTED')
        selected = os.environ.get('KAST_ACCEPTANCE_IDEA_HOME')
        home = admit_home(Path(selected)) if selected else provision(args.idea_cache)
        args.report.with_suffix('.inputs.json').write_text(json.dumps({
            'schemaVersion': 1, 'profile': args.profile, 'physicalMemoryBytes': memory,
            'ideaBuild': BUILD, 'ideaHome': str(home),
            'ideaMetadataSha256': digest(home / 'Resources/product-info.json'),
            'inputKind': 'explicit-local-home' if selected else 'verified-official-download',
            'archive': None if selected else {'url': URL, 'sha256': SHA256}}, indent=2) + '\n')
        subprocess.run([sys.executable, str(Path(__file__).with_name('test-model-input-startup.py')),
                        '--product', str(args.product), '--runtime', str(args.runtime),
                        '--idea-home', str(home), '--report', str(args.report), '--profile', args.profile,
                        '--harness-classpath-file', str(args.harness_classpath_file)], check=True)
    except (ValueError, OSError, subprocess.SubprocessError) as failure:
        prerequisite = args.report.with_suffix('.prerequisite.json')
        prerequisite.write_text(json.dumps({'status': 'rejected', 'reason': str(failure)[:512]}, indent=2) + '\n')
        print(f'two-workspace acceptance rejected; see {prerequisite}', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
