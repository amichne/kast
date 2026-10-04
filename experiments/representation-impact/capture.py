#!/usr/bin/env python3
"""One read-only public RPC capture against an already running, explicitly selected IDE."""
from dataclasses import asdict
import argparse
import json
import hashlib
import re
from pathlib import Path
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(REPO / 'experiments/host-observation'))
from reproduce_semantic_queries import digest, fresh
from capture_contract import (Captured, Correlation, Counter, Environment, ExpectedBasisUnavailable, ExpectedSeedBasis, Failure, Invocation,
                              NativeSeedBasis,
                              Observations, Rejected, RequestAdmitted, RpcOutcome, Uncertainty)

SCHEMA = REPO / 'app-server/src/main/resources/io/github/amichne/kast/appserver/query/query_symbols.parameters.json'
MAX_APPEND = 2 * 1024 * 1024
MAX_REQUEST = 1024 * 1024
MARKER = b'kast_semantic_read '
DIAGNOSTIC_FIELDS = frozenset(('schemaVersion', 'limits', 'pid', 'readId', 'correlation', 'durationNanos',
                              'stages', 'nativePhase', 'nativePhaseDurations', 'semanticEntry', 'semanticBudget',
                              'counters', 'gauges', 'terminations', 'outcome', 'unexpectedFailures'))


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('DUPLICATE_FIELD')
        value[key] = item
    return value


def validate_request(raw, schema_path=SCHEMA):
    import jsonschema
    try:
        schema_bytes = schema_path.read_bytes()
        schema = json.loads(schema_bytes, object_pairs_hook=unique_object)
        jsonschema.Draft202012Validator.check_schema(schema)
    except (OSError, ValueError, jsonschema.SchemaError):
        return Failure.SCHEMA_UNAVAILABLE
    try:
        if len(raw) > MAX_REQUEST:
            return Failure.REQUEST_SCHEMA_REJECTED
        value = json.loads(raw, object_pairs_hook=unique_object)
        jsonschema.Draft202012Validator(schema).validate(value)
    except (ValueError, UnicodeError, jsonschema.ValidationError):
        return Failure.REQUEST_SCHEMA_REJECTED
    return RequestAdmitted(len(raw), hashlib.sha256(schema_bytes).hexdigest())


def expected_seed_basis(response) -> ExpectedSeedBasis | ExpectedBasisUnavailable:
    document = response.get('document')
    accounting = document.get('impact_accounting') if isinstance(document, dict) else None
    if not isinstance(accounting, dict) or accounting.get('type') != 'INVESTIGATED':
        return ExpectedBasisUnavailable(Uncertainty.RESPONSE_BASIS_UNAVAILABLE)
    seeds = accounting.get('seeds')
    if not isinstance(seeds, list) or not 1 <= len(seeds) <= 1000:
        return ExpectedBasisUnavailable(Uncertainty.SEED_BASIS_UNAVAILABLE)
    bases = []
    required = {'type', 'root', 'host', 'epoch', 'contentView', 'referenceVersion'}
    for seed in seeds:
        enclosing = seed.get('enclosing') if isinstance(seed, dict) else None
        basis = enclosing.get('basis') if isinstance(enclosing, dict) else None
        if not isinstance(basis, dict):
            return ExpectedBasisUnavailable(Uncertainty.SEED_BASIS_UNAVAILABLE)
        if (set(basis) != required or basis.get('type') != 'LIVE'
                or basis.get('contentView') != 'SAVED_PSI_COMMITTED'
                or type(basis.get('referenceVersion')) is not int or basis['referenceVersion'] != 1):
            return ExpectedBasisUnavailable(Uncertainty.UNSUPPORTED_SEED_BASIS)
        if (not isinstance(basis['root'], str) or not basis['root'].startswith('/')
                or not isinstance(basis['host'], str)
                or re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', basis['host']) is None
                or type(basis['epoch']) is not int or basis['epoch'] < 0):
            return ExpectedBasisUnavailable(Uncertainty.UNSUPPORTED_SEED_BASIS)
        bases.append(NativeSeedBasis(**basis))
    if any(basis != bases[0] for basis in bases[1:]):
        return ExpectedBasisUnavailable(Uncertainty.CONFLICTING_SEED_BASES)
    return ExpectedSeedBasis(bases[0])


def select_receipts(receipts, pid, response, appended=0, malformed=0, initial=()):
    selected = tuple(r for r in receipts if type(r.get('pid')) is int and r['pid'] == pid)
    foreign = len(receipts) - len(selected)
    uncertainty = list(initial)
    if malformed:
        uncertainty.append(Uncertainty.MALFORMED_DIAGNOSTIC)
    if foreign:
        uncertainty.append(Uncertainty.FOREIGN_PID)
    expected = expected_seed_basis(response)
    correlation = Correlation.UNAVAILABLE
    if not selected:
        uncertainty.append(Uncertainty.NO_PID_RECEIPT)
    elif len(selected) != 1:
        correlation = Correlation.AMBIGUOUS
        uncertainty.append(Uncertainty.AMBIGUOUS_RECEIPTS)
    elif isinstance(expected, ExpectedBasisUnavailable):
        uncertainty.append(expected.reason)
    else:
        actual = selected[0].get('correlation', {})
        if (isinstance(actual, dict) and actual.get('type') == 'bound'
                and actual.get('host') == expected.basis.host and actual.get('epoch') == expected.basis.epoch):
            correlation = Correlation.MATCHED
        else:
            correlation = Correlation.MISMATCHED
            uncertainty.append(Uncertainty.BASIS_MISMATCH)
    counters = []
    if correlation is Correlation.MATCHED:
        raw_counts = selected[0].get('counters')
        seen = set()
        if not isinstance(raw_counts, list):
            uncertainty.append(Uncertainty.COUNTERS_UNAVAILABLE)
        else:
            for count in raw_counts:
                if (not isinstance(count, dict) or set(count) != {'counter', 'contributor', 'count'}
                        or not isinstance(count['counter'], str) or not isinstance(count['contributor'], str)
                        or type(count['count']) is not int or count['count'] < 0
                        or (count['counter'], count['contributor']) in seen):
                    uncertainty.append(Uncertainty.COUNTERS_UNAVAILABLE)
                    counters = []
                    break
                seen.add((count['counter'], count['contributor']))
                counters.append(Counter(**count))
            limits = selected[0].get('limits', [])
            ceiling = next((x.get('value') for x in limits
                            if isinstance(x, dict) and x.get('parameter') == 'DIAGNOSTIC_COUNT'), None) if isinstance(limits, list) else None
            if type(ceiling) is int and any(c.count >= ceiling for c in counters):
                uncertainty.append(Uncertainty.COUNTER_SATURATED)
    return Observations('SEMANTIC_READ_APPEND', appended, len(receipts), len(selected), foreign,
                        malformed, correlation, expected, tuple(counters), tuple(dict.fromkeys(uncertainty)), selected)


def appended_receipts(path, before, pid, response):
    """Reuse bounded host observation selection; audit skipped malformed records without retaining log text."""
    after = path.stat()
    if (after.st_dev, after.st_ino) != (before.st_dev, before.st_ino) or after.st_size < before.st_size:
        return select_receipts((), pid, response, initial=(Uncertainty.LOG_ROTATED,))
    appended = after.st_size - before.st_size
    if appended > MAX_APPEND:
        return select_receipts((), pid, response, appended, initial=(Uncertainty.LOG_APPEND_LIMIT,))
    with path.open('rb') as stream:
        stream.seek(before.st_size)
        lines = stream.read(appended).splitlines()
    structured, malformed = [], 0
    for line in lines:
        if MARKER not in line:
            continue
        try:
            record = json.loads(line.split(MARKER, 1)[1], object_pairs_hook=unique_object)
            if (not isinstance(record, dict) or set(record) != DIAGNOSTIC_FIELDS
                    or record.get('schemaVersion') != 6 or type(record.get('pid')) is not int):
                raise ValueError('INVALID_DIAGNOSTIC_SHAPE')
            structured.append(record)
        except (ValueError, UnicodeError):
            malformed += 1
    return select_receipts(structured, pid, response, appended, malformed)


def admit_response(raw):
    try:
        value = json.loads(raw, object_pairs_hook=unique_object)
        outcome = RpcOutcome(value['type'])
        expected = {'type', 'failure'} if outcome is RpcOutcome.RPC_REJECTED else {'type', 'document'}
        if set(value) != expected or (outcome is not RpcOutcome.RPC_REJECTED and not isinstance(value['document'], dict)):
            return Failure.INVALID_RPC_RESPONSE
        return outcome, value
    except (ValueError, KeyError, TypeError, UnicodeError):
        return Failure.INVALID_RPC_RESPONSE


def write_record(output, record):
    (output / 'capture.json').write_text(json.dumps(asdict(record), indent=2) + '\n')


def invoke(args, run=subprocess.run):
    output, invocation = None, None
    try:
        owned = args.owned_root.resolve(strict=True)
        fixture = args.fixture.resolve(strict=True)
        idea = args.idea_home.resolve(strict=True)
        log = args.idea_log.resolve(strict=True)
        if not fixture.is_dir() or not log.is_file() or args.idea_pid <= 0 or not 1 <= args.timeout <= 300:
            return Rejected(Failure.INPUT_UNAVAILABLE)
        output_path = args.output_dir.absolute()
        if not log.is_relative_to(owned) or not output_path.parent.resolve(strict=True).is_relative_to(owned):
            return Rejected(Failure.OUTSIDE_OWNED_ROOT)
        try:
            output = fresh(output_path)
        except FileExistsError:
            return Rejected(Failure.OUTPUT_NOT_FRESH)
        raw = args.request.read_bytes()
        (output / 'request.json').write_bytes(raw)
        admission = validate_request(raw)
        if isinstance(admission, Failure):
            result = Rejected(admission)
            write_record(output, result)
            return result
        home = owned / 'home'
        rpc = (args.rpc or home / '.local/share/kast/installation/bin/kast-tool-rpc').resolve(strict=True)
        java = (args.java_home or idea / 'jbr/Contents/Home').resolve(strict=True)
        if not rpc.is_relative_to(owned) or not home.is_dir() or not (java / 'bin/java').is_file():
            result = Rejected(Failure.OUTSIDE_OWNED_ROOT)
            write_record(output, result)
            return result
        # Query exact comm without arguments: never retain process argv or command-line secrets.
        try:
            observed = run(['/bin/ps', '-p', str(args.idea_pid), '-o', 'comm='], capture_output=True,
                           text=True, timeout=5, env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'})
        except subprocess.TimeoutExpired:
            result = Rejected(Failure.IDEA_OBSERVATION_TIMEOUT)
            write_record(output, result)
            return result
        except OSError:
            result = Rejected(Failure.IDEA_OBSERVATION_UNAVAILABLE)
            write_record(output, result)
            return result
        if observed.returncode != 0 or observed.stdout.strip() != str(idea / 'MacOS/idea'):
            result = Rejected(Failure.RUNNING_IDEA_MISMATCH)
            write_record(output, result)
            return result
        environment = Environment(str(home), str(owned / 'config'), str(owned / 'data'), str(owned / 'state'),
                                  str(owned / 'cache'), str(owned / 'run'), str(java),
                                  f'-Duser.home="{home}" -Djava.io.tmpdir="{owned / "tmp"}"', str(idea),
                                  str(java / 'bin') + ':/usr/bin:/bin:/usr/sbin:/sbin', str(owned / 'tmp'))
        for directory in ('config', 'data', 'state', 'cache', 'run', 'tmp'):
            (owned / directory).mkdir(mode=0o700, exist_ok=True)
        before = log.stat()
        argv = (str(rpc), 'call', 'query_symbols')
        started = time.monotonic_ns()
        process_failure = None
        try:
            process = run(argv, cwd=fixture, input=raw, capture_output=True, timeout=args.timeout, env=asdict(environment))
            stdout, stderr, code = process.stdout, process.stderr, process.returncode
            if code != 0:
                process_failure = Failure.PROCESS_EXIT_REJECTED
        except subprocess.TimeoutExpired as error:
            stdout, stderr, code = error.stdout or b'', error.stderr or b'', None
            process_failure = Failure.PROCESS_TIMEOUT
        except OSError:
            stdout, stderr, code = b'', b'', None
            process_failure = Failure.PROCESS_LAUNCH_REJECTED
        elapsed = time.monotonic_ns() - started
        (output / 'stdout.json').write_bytes(stdout)
        (output / 'stderr.txt').write_bytes(stderr)
        invocation = Invocation(argv, str(fixture), elapsed, code, 'stdout.json', 'stderr.txt', len(stdout), len(stderr),
                                digest(rpc), digest(output / 'request.json'), admission.schemaSha256, environment)
        if process_failure:
            result = Rejected(process_failure, invocation)
        else:
            admitted = admit_response(stdout)
            if isinstance(admitted, Failure):
                result = Rejected(admitted, invocation)
            else:
                outcome, response = admitted
                observations = appended_receipts(log, before, args.idea_pid, response)
                result = Captured(invocation, args.idea_pid, outcome, observations)
        write_record(output, result)
        return result
    except (OSError, ValueError):
        result = Rejected(Failure.IO_UNAVAILABLE, invocation)
        if output is not None:
            try:
                write_record(output, result)
            except OSError:
                pass  # Final stdout still carries the finite harness rejection when owned storage fails.
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    call = sub.add_parser('invoke')
    for name in ('owned-root', 'fixture', 'idea-home', 'idea-log', 'request', 'output-dir'):
        call.add_argument('--' + name, type=Path, required=True)
    call.add_argument('--idea-pid', type=int, required=True)
    call.add_argument('--rpc', type=Path)
    call.add_argument('--java-home', type=Path)
    call.add_argument('--timeout', type=int, default=120)
    result = invoke(parser.parse_args())
    print(json.dumps(asdict(result), indent=2))
    return 2 if isinstance(result, Rejected) else 0


if __name__ == '__main__':
    sys.exit(main())
