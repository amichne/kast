#!/usr/bin/env python3
"""Source-derived r1 oracle. No semantic expectations are inferred from candidate output."""
import argparse
from dataclasses import dataclass, asdict
from pathlib import Path
import hashlib
import json

A_NAMED = {
    'fixture.attributes.Attributes', 'fixture.attributes.MapAttributes', 'fixture.attributes.EmptyAttributes',
    *('fixture.attributes.' + x for x in ('getWithAttributes', 'testAttributes', 'ordinaryAttributes',
                                        'attributesFactory', 'inferredAttributes', 'helperRemoval')),
    *('fixture.attributes.Attributes.' + x for x in ('get', 'getOrNull', 'contains', 'put', 'set', 'remove',
                                                    'take', 'takeOrNull', 'computeIfAbsent', 'allKeys')),
    *('fixture.attributes.' + owner + '.' + name for owner in ('MapAttributes', 'EmptyAttributes')
      for name in ('getOrNull', 'contains', 'put', 'remove', 'computeIfAbsent', 'allKeys')),
}
B_NAMED = {
    *('fixture.flow.' + x for x in ('FlowCollector', 'namedFlow', 'collectIndexed', 'collectSecond',
                                  'NamedCollector')),
    'fixture.flow.Flow.collect', 'fixture.flow.SourceFlow.collect',
    'fixture.flow.FlowCollector.emit', 'fixture.flow.NamedCollector.emit',
}

def utf(text):
    return len(text.encode('utf-16-le')) // 2

def span(text, start, end):
    return {'startInclusive': utf(text[:start]), 'endExclusive': utf(text[:end])}

def brace_end(text, start):
    # The frozen declarations contain no string/comment brace tokens in their object bodies.
    pos = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[pos] == '{') - (text[pos] == '}')
        pos += 1
    return pos

def named(rows):
    return {row['signature']['qualifiedIdentity'] for row in rows if 'qualifiedIdentity' in row['signature']}

@dataclass(frozen=True)
class Verification:
    type: str
    fixtureManifestSha256: str
    requestsManifestSha256: str
    checks: tuple[str, ...]
    counts: tuple[int, int]

def verify(root, phase):
    manifest = json.loads((root / 'manifest.json').read_text())
    fixture = root / 'fixture'
    for entry in manifest['files']:
        assert hashlib.sha256((fixture / entry['path']).read_bytes()).hexdigest() == entry['sha256'], entry['path']
    frozen_requests = {x['label']: x['payload'] for x in json.loads((root/'requests-manifest.json').read_text())['requests']}
    receipts = [json.loads(p.read_text()) for p in sorted((root / (phase + '-observations')).glob('call-*.json'))]
    def documents(label):
        return [x['document'] for x in receipts if x['label'] == label or x['label'].startswith(label + '.')]
    def rows(label):
        # Public pages may carry evidence-only windows; each exact row must occur once across a presentation.
        return [item for d in documents(label) for item in d.get('items', [])]
    def first(label):
        return documents(label)[0]
    checks = []
    for label in ('A.TRACE', 'B.TRACE', 'A.IMPLEMENTATIONS', 'B.IMPLEMENTATIONS', 'B.OVERRIDES',
                  'A.library.CALLEES', 'A.helper.CALLEES', 'N.conditional.CALLEES', 'N.spring.IMPLEMENTATIONS'):
        assert first(label)['status'] == 'complete', label
        assert first(label)['coverage']['exhaustive'] is True, label
    a = rows('A.TRACE'); b = rows('B.TRACE')
    assert named(a) == A_NAMED, (named(a) - A_NAMED, A_NAMED - named(a))
    local_a = [x for x in a if 'qualifiedIdentity' not in x['signature']]
    assert len(local_a) == 1 and local_a[0]['name'] == 'previous' and local_a[0]['signature']['type'] == 'LOCAL_PROPERTY'
    text = (fixture / 'src/main/kotlin/fixture/attributes/Implementations.kt').read_text()
    start = text.index('val previous = getOrNull(key)')
    assert local_a[0]['location']['range'] == span(text, start, start + len('val previous = getOrNull(key)'))
    assert named(b) == B_NAMED, (named(b) - B_NAMED, B_NAMED - named(b))
    assert len(a) == 32 and len(b) == 13
    checks.append('Exact broad TRACE sets follow seed, member, implementation, reference and caller fanout from frozen source')
    assert named(rows('A.IMPLEMENTATIONS')) == {'fixture.attributes.MapAttributes', 'fixture.attributes.EmptyAttributes'}
    text = (fixture / 'src/main/kotlin/fixture/flow/Collect.kt').read_text()
    objects = []; methods = []; owners = []
    cursor = 0
    for owner in ('collectIndexed', 'collectSecond'):
        start = text.index('object : FlowCollector<T>', cursor)
        end = brace_end(text, start)
        objects.append(span(text, start, end))
        method = text.index('override suspend fun emit', start)
        methods.append(span(text, method, text.index('\n', method)))
        owner_start = text.rfind('public suspend inline fun' if owner == 'collectIndexed' else '// Authored same-shaped control', 0, start)
        owners.append(span(text, owner_start, end + 1))
        cursor = end
    implementation_rows = [x for x in rows('B.IMPLEMENTATIONS') if x['signature']['type'] == 'ANONYMOUS_OBJECT']
    override_rows = [x for x in rows('B.OVERRIDES') if x['signature']['type'] == 'LOCAL_FUNCTION']
    assert len(implementation_rows) == 2 and len(override_rows) == 2
    assert named(rows('B.IMPLEMENTATIONS')) == {'fixture.flow.NamedCollector'}
    assert named(rows('B.OVERRIDES')) == {'fixture.flow.NamedCollector.emit'}
    implementation_rows.sort(key=lambda x: x['location']['range']['startInclusive'])
    override_rows.sort(key=lambda x: x['location']['range']['startInclusive'])
    for index, (obj, method) in enumerate(zip(implementation_rows, override_rows)):
        assert obj['location']['range'] == objects[index]
        assert method['location']['range'] == methods[index]
        assert obj['signature']['address']['ownerRange'] == owners[index]
        assert method['signature']['address']['ownerRange'] == objects[index]
        assert obj['signature']['supertypes'] == ['fixture/flow/FlowCollector<T>']
        identity = obj['connections'][0]['source']['compilerEvidence']['identity']
        assert method['signature']['address']['ownerIdentity'] == identity
        assert method['connections'][0]['target']['compilerEvidence']['signature']['qualifiedIdentity'] == 'fixture.flow.FlowCollector.emit'
    assert implementation_rows[0]['signature']['address']['ownerIdentity'] != implementation_rows[1]['signature']['address']['ownerIdentity']
    checks.append('Both source-distinct objects and overrides retain exact compiler owner and UTF-16 ranges')
    assert named(rows('A.library.CALLEES')) == {'fixture.attributes.Attributes.get', 'fixture.attributes.Attributes.remove'}
    assert named(rows('A.helper.CALLEES')) == {'fixture.attributes.Attributes.get', 'fixture.attributes.Attributes.remove', 'fixture.attributes.insideAlso'}
    callback_rows = [cb for d in documents('A.TRACE') for obs in d.get('relation_observations', [])
                     for cb in obs.get('callback_observations', [])]
    declared = [cb for cb in callback_rows if cb.get('flow', {}).get('binding', {}).get('type') == 'DEPENDENCY_CONTRACT']
    assert len(declared) >= 2
    for cb in declared:
        binding = cb['flow']['binding']
        assert binding['provenance'] == 'KOTLIN_BINARY_CONTRACT' and binding['invocation_kind'] == 'EXACTLY_ONCE'
        assert binding['target']['compiler_evidence']['signature']['qualifiedIdentity'] == 'kotlin.also'
        assert binding['position'] == 0 and len(binding['class_digest']) == 64
        assert binding['basis'] == cb['flow']['basis']
        import zipfile
        archive, member = binding['target']['file'].removeprefix('jar://').split('!/', 1)
        archive_path = Path(archive)
        assert archive_path.is_relative_to(root/'gradle'), 'DEPENDENCY_NOT_OWNED'
        with zipfile.ZipFile(archive_path) as jar:
            assert hashlib.sha256(jar.read(member)).hexdigest() == binding['class_digest']
        assert cb['flow']['invocations'] == [] and cb['flow']['obligations'] == []
        assert cb['flow']['scan'] == 'EXHAUSTIVE'
        assert cb['target']['compiler_target']['compiler_evidence']['signature']['qualifiedIdentity'] == 'fixture.attributes.Attributes.remove'
    checks.append('Library effect remains declared-contract evidence, without a dependency node or fabricated body invocation')
    stored = first('N.stored.CALLEES')
    assert stored['status'] == 'rejected'
    assert stored['rejection']['detail']['cause']['graphFailure']['cause']['obligations'] == ['PARAMETER_ESCAPES', 'NO_INVOCATION_PROVEN']
    assert 'fixture.shadow.negativeSink' not in named(rows('N.stored.CALLEES'))
    assert named(rows('N.conditional.CALLEES')) == {'fixture.shadow.also'}
    assert rows('N.spring.IMPLEMENTATIONS') == []
    assert first('B.named.CALLERS')['status'] == 'rejected'
    assert first('B.named.CALLERS')['rejection']['detail']['cause']['graphFailure']['cause']['obligations'] == ['NESTED_CALLBACK_EXECUTION']
    checks.append('Stored, conditional, anonymous callback activation and empty Spring controls preserve qualifications')
    for receipt in receipts:
        request = receipt['payload']['request']
        if request['type'] == 'RUN':
            assert receipt['payload'] == frozen_requests[receipt['label']], 'FROZEN_REQUEST_CHANGED'
            assert request['executionBudget'] == manifest['grant']
        if '.recovery' in receipt['label']:
            assert receipt['document']['interpretation']['type'] == 'POLICY_REJECTED_EVIDENCE'
    checks.append('Frozen RUN grants and rejected-evidence interpretation are preserved')
    value = Verification('TRACE_CANDIDATE_ASSERTIONS', hashlib.sha256((root/'manifest.json').read_bytes()).hexdigest(),
                         hashlib.sha256((root/'requests-manifest.json').read_bytes()).hexdigest(), tuple(checks), (len(a), len(b)))
    (root / (phase + '-assertions.json')).write_text(json.dumps(asdict(value), indent=2) + '\n')
    return value

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--owned-root', type=Path, required=True)
    parser.add_argument('--phase', default='candidate')
    args = parser.parse_args()
    print(verify(args.owned_root.resolve(), args.phase))
