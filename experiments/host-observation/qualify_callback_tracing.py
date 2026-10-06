#!/usr/bin/env python3
"""Installed callback qualification using the existing schema admission and replay drainer.

The source oracle is independent of the returned graph. Every public page and issued
continuation is retained. This runner neither installs nor restarts the user's IDE.
"""
import argparse
from collections import Counter
from contextlib import closing
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re

import jsonschema
import reproduce_semantic_queries as replay


@dataclass(frozen=True)
class Budget:
    maxElapsedMs: int = 20000
    maxWorkUnits: int = 20000
    maxResults: int = 1
    maxReturnedBytes: int = 524288


@dataclass(frozen=True)
class RelationStep:
    relation: str
    type: str = 'EXPAND_RELATION'
    expansionScope: dict | None = None


@dataclass(frozen=True)
class WalkStep:
    relation: str
    type: str = 'WALK'
    maximumDepth: int = 1
    expansionScope: dict | None = None


@dataclass(frozen=True)
class Oracle:
    owner: str
    policy: str
    reason: str | None
    binding: str
    invocation: bool
    obligation: str | tuple[str, ...] | None = None
    occurrences: int = 1
    body_expression: str | None = None
    forbidden_obligations: tuple[str, ...] = ()
    scan: str | None = None
    forwarding_receivers: tuple[str, ...] = ()
    exhaustive_budget: bool = False
    default_expression: str | None = None
    direct_anonymous_owner: bool = False
    direct_invocation: str = '({ inlineTarget() })()'
    policy_cause: str | None = None
    binding_cause: str | None = None
    named_edge: bool | None = None
    forwarding_owner_expressions: tuple[str, ...] = ()


@dataclass(frozen=True)
class ParameterOracle:
    definition: str
    parameter: str
    position: int
    invocation: str
    anonymous_owner: bool = False


FIXTURE_ORACLES = (
    Oracle('stdlibInline', 'ADMITTED_INLINE', None, 'UNAVAILABLE', False, ('EXTERNAL_CALLABLE', 'OUTSIDE_DOMAIN')),
    Oracle('callbackInline', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True),
    Oracle('homonymousInline', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True),
    Oracle('noinlineBoundary', 'EXCLUDED', 'NOINLINE_ARGUMENT', 'BOUND', True),
    Oracle('crossinlineBoundary', 'EXCLUDED', 'CROSSINLINE_ARGUMENT', 'BOUND', True),
    Oracle('explicitInline', 'ADMITTED_INLINE', None, 'BOUND', True),
    Oracle('nestedInline', 'ADMITTED_INLINE', None, 'BOUND', True, 'NESTED_CALLBACK_EXECUTION'),
    Oracle('repeatedInline', 'ADMITTED_INLINE', None, 'BOUND', True, occurrences=2),
    Oracle('unsupportedOuter', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True, 'NESTED_CALLBACK_EXECUTION'),
    Oracle('storedInline', 'EXCLUDED', 'STORED_CALLBACK', 'UNAVAILABLE', False, 'STORED_CALLBACK'),
    Oracle('returnedInline', 'EXCLUDED', 'RETURNED_CALLBACK', 'UNAVAILABLE', False, 'RETURNED_CALLBACK'),
    Oracle('immediateLiteralCallback', 'ADMITTED_DIRECT', None, 'DIRECT', False,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'NO_INVOCATION_PROVEN'), scan='NOT_APPLICABLE'),
    Oracle('explicitLiteralCallback', 'ADMITTED_DIRECT', None, 'DIRECT', False,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'NO_INVOCATION_PROVEN'), scan='NOT_APPLICABLE',
           direct_invocation='({ inlineTarget() }).invoke()'),
    Oracle('explicitAnonymousCallback', 'ADMITTED_DIRECT', None, 'DIRECT', False,
           body_expression='fun(): String { return inlineTarget() }',
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'NO_INVOCATION_PROVEN'), scan='NOT_APPLICABLE',
           direct_invocation='(fun(): String { return inlineTarget() }).invoke()'),
    Oracle('storedParameterCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True),
    Oracle('explicitParameterCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True),
    Oracle('mutableParameterCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', False, 'PARAMETER_ESCAPES'),
    Oracle('storedOperationCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', False, 'PARAMETER_ESCAPES'),
    Oracle('selectedParameterCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True),
    Oracle('uninvokedParameterCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', False,
           forbidden_obligations=('NO_INVOCATION_PROVEN',), scan='EXHAUSTIVE'),
    Oracle('unresolvedCallback', 'UNAVAILABLE', None, 'UNAVAILABLE', False, 'UNRESOLVED_ARGUMENT_MAPPING'),
    Oracle('labelledInline', 'ADMITTED_INLINE', None, 'BOUND', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING')),
    Oracle('labelledOrdinary', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING')),
    Oracle('nestedDirectInline', 'ADMITTED_DIRECT', None, 'DIRECT', False, 'NESTED_CALLBACK_EXECUTION',
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'NO_INVOCATION_PROVEN'),
           scan='NOT_APPLICABLE', exhaustive_budget=True, direct_anonymous_owner=True),
    Oracle('nestedDirectOrdinary', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'DIRECT', False, 'NESTED_CALLBACK_EXECUTION',
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'NO_INVOCATION_PROVEN'),
           scan='NOT_APPLICABLE', exhaustive_budget=True, direct_anonymous_owner=True),
    Oracle('anonymousFunInline', 'ADMITTED_INLINE', None, 'BOUND', True,
           body_expression='fun(): String { return inlineTarget() }',
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING')),
    Oracle('anonymousFunOrdinary', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True,
           body_expression='fun(): String { return inlineTarget() }',
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING')),
    Oracle('defaultInlineDefinition', 'EXCLUDED', 'DEFAULT_PARAMETER', 'DEFAULT', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', exhaustive_budget=True, default_expression='{ inlineTarget() }'),
    Oracle('defaultOrdinaryDefinition', 'EXCLUDED', 'DEFAULT_PARAMETER', 'DEFAULT', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', exhaustive_budget=True, default_expression='{ inlineTarget() }'),
    Oracle('suppliedDefaultInline', 'ADMITTED_INLINE', None, 'BOUND', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', exhaustive_budget=True, default_expression='{ inlineTarget() }'),
    Oracle('suppliedDefaultOrdinary', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', exhaustive_budget=True, default_expression='{ inlineTarget() }'),
    Oracle('forwardedInline', 'ADMITTED_INLINE', None, 'BOUND', True,
           forbidden_obligations=('PARAMETER_ESCAPES', 'NO_INVOCATION_PROVEN', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', forwarding_receivers=('ordinaryInline(block:',), exhaustive_budget=True),
    Oracle('forwardedInlineTwice', 'ADMITTED_INLINE', None, 'BOUND', True,
           forbidden_obligations=('PARAMETER_ESCAPES', 'NO_INVOCATION_PROVEN', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', forwarding_receivers=('forwardingInlineHelper', 'ordinaryInline(block:'),
           exhaustive_budget=True),
    Oracle('nestedNamedForwardingCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True,
           'NESTED_CALLBACK_EXECUTION',
           forbidden_obligations=('PARAMETER_ESCAPES', 'NO_INVOCATION_PROVEN', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', forwarding_receivers=('ordinaryCallback',), exhaustive_budget=True,
           forwarding_owner_expressions=('fun deferred(): String = ordinaryCallback(block)',)),
    Oracle('nestedCrossinlineCallback', 'EXCLUDED', 'CROSSINLINE_ARGUMENT', 'BOUND', True,
           'NESTED_CALLBACK_EXECUTION', scan='EXHAUSTIVE', exhaustive_budget=True),
)

METHOD_DEFAULT_ORACLES = tuple(
    Oracle(owner, 'EXCLUDED', 'DEFAULT_PARAMETER', 'DEFAULT', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', exhaustive_budget=True, default_expression='{ client.fetch() }')
    for owner in ('defaultMethodInlineDefinition', 'defaultMethodOrdinaryDefinition')
)

IGNORED_INVOKE_ORACLES = tuple(
    Oracle(owner, 'UNAVAILABLE', None, 'UNAVAILABLE', False, 'UNRESOLVED_ARGUMENT_MAPPING',
           scan='INCOMPLETE', exhaustive_budget=True, policy_cause='UNSUPPORTED_BOUNDARY',
           binding_cause='UNRESOLVED_ARGUMENT_MAPPING', named_edge=False)
    for owner in ('ignoredExplicitCallback', 'ignoredImplicitCallback')
)



# Authored receiving callable/parameter/invocation expectations, independent of responses.
RECEIVERS = {
    'callbackInline': ('ordinaryCallback', 'block', 'block()'),
    'homonymousInline': ('ordinaryInline(marker:', 'block', 'block()'),
    'noinlineBoundary': ('noinlineHelper', 'block', 'block()'),
    'crossinlineBoundary': ('crossinlineHelper', 'block', 'block()'),
    'explicitInline': ('ordinaryInline(block:', 'block', 'block()'),
    'nestedInline': ('ordinaryInline(block:', 'block', 'block()'),
    'repeatedInline': ('ordinaryInline(block:', 'block', 'block()'),
    'unsupportedOuter': ('ordinaryInline(block:', 'block', 'block()'),
    'storedParameterCallback': ('storedParameter', 'block', 'saved()'),
    'explicitParameterCallback': ('explicitParameter', 'block', 'block.invoke()'),
    'mutableParameterCallback': ('mutableParameter', 'block', None),
    'storedOperationCallback': ('storeOperation', 'block', None),
    'selectedParameterCallback': ('selectedOperation', 'selected', 'selected()'),
    'uninvokedParameterCallback': ('selectedOperation', 'unused', None),
    'labelledInline': ('ordinaryInline(block:', 'block', 'block()'),
    'labelledOrdinary': ('ordinaryCallback', 'block', 'block()'),
    'anonymousFunInline': ('ordinaryInline(block:', 'block', 'block()'),
    'anonymousFunOrdinary': ('ordinaryCallback', 'block', 'block()'),
    'defaultInlineDefinition': ('defaultInlineDefinition', 'block', 'block()'),
    'defaultOrdinaryDefinition': ('defaultOrdinaryDefinition', 'block', 'block()'),
    'suppliedDefaultInline': ('defaultInlineDefinition', 'block', 'block()'),
    'suppliedDefaultOrdinary': ('defaultOrdinaryDefinition', 'block', 'block()'),
    'forwardedInline': ('forwardingInlineHelper', 'block', 'block()'),
    'forwardedInlineTwice': ('forwardingInlineTwiceHelper', 'block', 'block()'),
    'nestedNamedForwardingCallback': ('nestedNamedForwardingHelper', 'block', 'block()'),
    'nestedCrossinlineCallback': ('nestedCrossinlineHelper', 'block', 'block()'),
    'defaultMethodInlineDefinition': ('defaultMethodInlineDefinition', 'block', 'block()'),
    'defaultMethodOrdinaryDefinition': ('defaultMethodOrdinaryDefinition', 'block', 'block()'),
    'read': ('nativeBoundary', 'operation', 'operation()'),
}

DEFAULT_CALL_SITES = (
    ('omittedDefaultInline', 'defaultInlineDefinition()', 'inlineTarget'),
    ('replacedDefaultInline', 'defaultInlineDefinition { "replacement" }', 'inlineTarget'),
    ('omittedDefaultOrdinary', 'defaultOrdinaryDefinition()', 'inlineTarget'),
    ('replacedDefaultOrdinary', 'defaultOrdinaryDefinition { "replacement" }', 'inlineTarget'),
    ('omittedDefaultMethodInline', 'defaultMethodInlineDefinition(client)', 'fetch'),
    ('replacedDefaultMethodInline', 'defaultMethodInlineDefinition(client) { "replacement" }', 'fetch'),
    ('omittedDefaultMethodOrdinary', 'defaultMethodOrdinaryDefinition(client)', 'fetch'),
    ('replacedDefaultMethodOrdinary', 'defaultMethodOrdinaryDefinition(client) { "replacement" }', 'fetch'),
)

PARAMETER_CALLEE_ORACLES = (
    ParameterOracle('ordinaryInline(block:', 'block: () -> String', 0, 'block()'),
    ParameterOracle('ordinaryInline(marker:', 'block: () -> String', 1, 'block()'),
    ParameterOracle('ordinaryCallback', 'block: () -> String', 0, 'block()'),
    ParameterOracle('noinlineHelper', 'noinline block: () -> String', 0, 'block()'),
    ParameterOracle('crossinlineHelper', 'crossinline block: () -> String', 0, 'block()'),
    ParameterOracle('defaultInlineDefinition', 'block: () -> String = { inlineTarget() }', 0, 'block()'),
    ParameterOracle('defaultOrdinaryDefinition', 'block: () -> String = { inlineTarget() }', 0, 'block()'),
    ParameterOracle('defaultMethodInlineDefinition', 'block: () -> String = { client.fetch() }', 1, 'block()'),
    ParameterOracle('defaultMethodOrdinaryDefinition', 'block: () -> String = { client.fetch() }', 1, 'block()'),
    ParameterOracle('explicitParameter', 'block: () -> String', 0, 'block.invoke()'),
    ParameterOracle('selectedOperation', 'selected: () -> String', 1, 'selected()'),
    ParameterOracle('nestedCrossinlineHelper', 'crossinline block: () -> String', 0, 'block()', True),
)


def next_function(text, start):
    """The fixture oracle owns top-level declarations, including inline helpers."""
    match = re.search(r'\n(?:inline )?fun ', text[start:])
    return len(text) if match is None else start + match.start()


def authored_region(root, owner, production=False):
    relative = ('relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/'
                'IntellijValueFlowCompilerAdapter.kt' if production else
                'logging/src/main/kotlin/fixture/calls/' +
                ('ReadUnresolvedCallbacks.kt' if owner == 'unresolvedCallback' else 'ReadKotlinCalls.kt'))
    file = root / relative
    text = file.read_text()
    marker = 'suspend fun read(' if production else 'fun ' + owner + '('
    start = text.index(marker)
    end = text.find('\n    private fun readPrepared', start) if production else next_function(text, start + len(marker))
    return file, text, start, len(text) if end < 0 else end


def site_key(site):
    return (site['file'], site['range']['startInclusive'], site['range']['endExclusive'])


def expected_occurrences(root, oracle, target, production):
    file, text, start, end = authored_region(root, oracle.owner, production)
    expected = []
    position = start
    while True:
        position = text.find(target + '(', position, end)
        if position < 0:
            break
        utf16 = len(text[:position].encode('utf-16-le')) // 2
        expected.append((str(file), utf16, utf16 + len(target)))
        position += len(target)
    assert len(expected) == oracle.occurrences, (oracle.owner, expected)
    return Counter(expected)


def check_mapping(observation, oracle, invocation=True):
    if oracle.binding not in ('BOUND', 'DEFAULT'):
        return
    receiver, parameter, expression = RECEIVERS[oracle.owner]
    binding = observation['flow']['binding']
    if binding['type'] == 'DEFAULT':
        binding = binding['parameter']
    expected_position = (5 if oracle.owner == 'read' else 1 if oracle.owner in (
        'homonymousInline', 'selectedParameterCallback', 'defaultMethodInlineDefinition',
        'defaultMethodOrdinaryDefinition') else 0)
    assert binding['position'] == expected_position, binding
    assert callable_name(binding['callable']) == receiver.split('(')[0], binding
    declaration = binding['callable']['declaration']
    text = Path(declaration['file']).read_text()
    marker = 'fun <Result> nativeBoundary(' if oracle.owner == 'read' else 'fun ' + receiver
    start = text.index(marker)
    end = next_function(text, start + len(marker))
    parameter_site = binding['parameter']
    bounds = parameter_site['range']
    modifier = {'noinlineHelper': 'noinline ', 'crossinlineHelper': 'crossinline ',
                'nestedCrossinlineHelper': 'crossinline '}.get(receiver, '')
    parameter_text = modifier + parameter + ': () -> ' + ('Result' if oracle.owner == 'read' else 'String')
    if oracle.default_expression:
        parameter_text += ' = ' + oracle.default_expression
    parameter_start = len(text[:text.index(parameter_text, start, end)].encode('utf-16-le')) // 2
    assert bounds == dict(startInclusive=parameter_start, endExclusive=parameter_start + len(parameter_text)), (parameter_site, parameter_text)
    if expression and invocation:
        if oracle.forwarding_receivers:
            receiver = oracle.forwarding_receivers[-1]
            declaration = next(iter(observation['flow']['invocations']))['owner']['callable']['declaration']
            text = Path(declaration['file']).read_text()
            marker = 'fun ' + receiver
            start = text.index(marker)
            end = next_function(text, start + len(marker))
        offset = text.index(expression, start, end)
        utf16 = len(text[:offset].encode('utf-16-le')) // 2
        assert Counter(site_key(i['occurrence']) for i in observation['flow']['invocations']) == Counter([
            (declaration['file'], utf16, utf16 + len(expression))]), observation['flow']


def source_text(site):
    bounds = site['range']
    text = Path(site['file']).read_text().encode('utf-16-le')
    return text[bounds['startInclusive'] * 2:bounds['endExclusive'] * 2].decode('utf-16-le')


def check_forwardings(invocation, observation, oracle):
    forwardings = invocation['forwardings']
    assert len(forwardings) == len(oracle.forwarding_receivers), forwardings
    previous = observation['flow']['binding']
    if oracle.forwarding_owner_expressions:
        assert len(oracle.forwarding_owner_expressions) == len(forwardings), oracle
    for index, (forwarding, receiver) in enumerate(zip(forwardings, oracle.forwarding_receivers)):
        source, argument, target = forwarding['source'], forwarding['argument'], forwarding['target']
        assert source['callable'] == previous['callable'], forwarding
        assert source['position'] == previous['position'], forwarding
        assert source['parameter'] == previous['parameter'], forwarding
        assert source_text(argument) == 'block', forwarding
        assert argument['candidateSelector'], forwarding
        assert callable_name(target['callable']) == receiver.split('(')[0], forwarding
        assert target['position'] == 0, forwarding
        assert source_text(target['parameter']) == 'block: () -> String', forwarding
        assert source_text(target['invocation_occurrence']) == receiver.split('(')[0] + '(block)', forwarding
        assert target['invocation_owner']['type'] == 'NAMED', forwarding
        owner = target['invocation_owner']['callable']
        if oracle.forwarding_owner_expressions:
            assert source_text(owner['declaration']) == oracle.forwarding_owner_expressions[index], forwarding
            assert owner != source['callable'], forwarding
            assert owner['declaration']['file'] == source['callable']['declaration']['file'], forwarding
            assert source['callable']['declaration']['range']['startInclusive'] <= owner['declaration']['range']['startInclusive'], forwarding
            assert owner['declaration']['range']['endExclusive'] <= source['callable']['declaration']['range']['endExclusive'], forwarding
        else:
            assert owner == source['callable'], forwarding
        previous = target
    if forwardings:
        assert invocation['owner']['type'] == 'NAMED', invocation
        assert invocation['owner']['callable'] == previous['callable'], invocation


def request(name, directory, direction, walk, budget, source=None):
    scope = dict(type='DIRECTORY', value=directory, containment='RECURSIVE')
    case = replay.Case(name, replay.search(name, scope), select=())
    _, _, payload = replay.invocation(case, replay.ToolSurface.PUBLIC)
    payload['verbose'] = True
    run = payload['request']
    if source is not None:
        run['source'] = source
    run['steps'] = [asdict((WalkStep if walk else RelationStep)(direction, expansionScope=dict(type='WORKSPACE')))]
    run['output'] = dict(type='TRAVERSAL_RECORDS' if walk else 'OCCURRENCES')
    run['retention'] = 'RETAIN'
    run['executionBudget'] = asdict(budget)
    return payload


def observations(pages):
    for page in pages:
        for relation in page.get('relation_observations', []):
            yield from relation.get('callback_observations', [])
        for walk in page.get('walk_observations', []):
            for item in walk.get('callback_observations', []):
                yield item['observation']


def callable_observations(pages):
    for page in pages:
        for relation in page.get('relation_observations', []):
            yield from relation.get('callable_observations', [])
        for walk in page.get('walk_observations', []):
            for item in walk.get('callable_observations', []):
                yield item['observation']


def named_relations(pages):
    for page in pages:
        for item in page['items']:
            if item['type'] == 'occurrence':
                yield item['relation']
            elif item['type'] == 'traversal_record':
                yield item['record']['relation']


def check_named_edge(pages, oracle, target):
    if oracle.named_edge is None:
        return
    matched = [fact for fact in named_relations(pages)
               if fact['source']['name'] == oracle.owner and fact['target']['name'] == target]
    assert bool(matched) == oracle.named_edge, (oracle.owner, target, matched)


def callable_name(value):
    return value['compiler_target']['name']


def check(observation, oracle, target, budget=None):
    assert callable_name(observation['target']) == target, observation
    assert callable_name(observation['lexical_owner']) == oracle.owner, observation
    policy = observation['named_policy']
    assert policy['type'] == oracle.policy, policy
    if oracle.reason:
        assert policy['reason'] == oracle.reason, policy
    if oracle.policy_cause:
        assert policy['cause'] == oracle.policy_cause, policy
    site = observation['occurrence']
    text = Path(site['file']).read_text()
    bounds = site['range']
    assert text.encode('utf-16-le')[bounds['startInclusive'] * 2:bounds['endExclusive'] * 2].decode('utf-16-le') == target, site
    body = observation['callback_body']
    assert body['file'] == site['file']
    assert body['range']['startInclusive'] <= bounds['startInclusive'] < bounds['endExclusive'] <= body['range']['endExclusive']
    assert site['candidateSelector'] and body['candidateSelector']
    target_offset = len(text.encode('utf-16-le')[:bounds['startInclusive'] * 2].decode('utf-16-le'))
    if oracle.owner == 'read':
        lambda_start = text.index('{\n                val started', text.index('suspend fun read('))
        lambda_end = text.index('\n            .also', lambda_start)
        lambda_end = text.rfind('}', lambda_start, lambda_end) + 1
    elif oracle.body_expression:
        region_start = text.index('fun ' + oracle.owner + '(')
        region_end = next_function(text, region_start + len('fun ' + oracle.owner + '('))
        lambda_start = text.index(oracle.body_expression, region_start, region_end)
        lambda_end = lambda_start + len(oracle.body_expression)
    else:
        lambda_start = text.rfind('{', 0, target_offset)
        lambda_end = text.index('}', target_offset) + 1
    expected_body = dict(startInclusive=len(text[:lambda_start].encode('utf-16-le')) // 2,
                         endExclusive=len(text[:lambda_end].encode('utf-16-le')) // 2)
    assert body['range'] == expected_body, (body, expected_body)
    flow = observation['flow']
    assert flow['type'] == 'OBSERVED', flow
    assert flow['body']['occurrence']['range'] == body['range']
    assert flow['body']['compiler_evidence']['identity'].startswith('canonical-signature-sha256-v1|')
    assert flow['body']['compiler_evidence']['signature']['qualifiedIdentity'] == (
        'anonymous@' + body['file'] + '#' + str(expected_body['startInclusive']) + ':' +
        str(expected_body['endExclusive']))
    assert flow['binding']['type'] == oracle.binding, flow
    if oracle.binding_cause:
        assert flow['binding']['cause'] == oracle.binding_cause, flow
    if oracle.scan:
        assert flow['scan'] == oracle.scan, flow
    if oracle.binding == 'DEFAULT':
        assert site_key(flow['binding']['default_value']) == site_key(body), flow
        assert source_text(flow['binding']['default_value']) == oracle.default_expression, flow
    if oracle.binding == 'DIRECT':
        binding = flow['binding']
        assert source_text(binding['occurrence']) == oracle.direct_invocation, binding
        assert binding['owner']['type'] == ('ANONYMOUS' if oracle.direct_anonymous_owner else 'NAMED'), binding
        if oracle.direct_anonymous_owner:
            assert source_text(binding['owner']['occurrence']) == '{ ({ inlineTarget() })() }', binding
        else:
            assert callable_name(binding['owner']['callable']) == oracle.owner, binding
    if oracle.owner in ('selectedParameterCallback', 'uninvokedParameterCallback'):
        assert flow['binding']['position'] == (1 if oracle.owner == 'selectedParameterCallback' else 0), flow
    # Authored scans retain the outer anonymous owner or immutable alias before
    # their invocation. These two facts share one result allowance.
    one_result = budget is not None and budget.maxResults == 1
    invocation_limited = one_result and oracle.owner in (
        'nestedInline', 'unsupportedOuter', 'storedParameterCallback')
    expected_invocation = oracle.invocation and not invocation_limited
    assert bool(flow['invocations']) == expected_invocation, flow
    check_mapping(observation, oracle, expected_invocation)
    if invocation_limited:
        assert {'RESULT_LIMIT_REACHED', 'NO_INVOCATION_PROVEN'} <= set(flow['obligations']), flow
    if oracle.obligation:
        expected_causes = (oracle.obligation,) if isinstance(oracle.obligation, str) else oracle.obligation
        assert set(expected_causes) & set(flow['obligations']), flow
    assert not set(oracle.forbidden_obligations) & set(flow['obligations']), flow
    if oracle.owner == 'storedParameterCallback' and expected_invocation:
        assert any(i.get('callable_transfers') for i in flow['invocations']), flow
    for invocation in flow['invocations']:
        check_forwardings(invocation, observation, oracle)
        assert invocation['occurrence']['candidateSelector']
        owner = invocation['owner']
        exact = owner['occurrence'] if owner['type'] == 'ANONYMOUS' else owner['callable']['declaration']
        assert exact['file'] == invocation['occurrence']['file']
        assert exact['range']['startInclusive'] <= invocation['occurrence']['range']['startInclusive']
        assert invocation['occurrence']['range']['endExclusive'] <= exact['range']['endExclusive']
    anonymous_owners = [flow['binding']['invocation_owner']] if flow['binding']['type'] == 'BOUND' else (
        [flow['binding']['owner']] if flow['binding']['type'] == 'DIRECT' else [])
    anonymous_owners.extend(i['owner'] for i in flow['invocations'])
    primary_supply = {'storedInline': 'STORED', 'returnedInline': 'RETURNED'}.get(oracle.owner)
    if primary_supply:
        anonymous_owners.append(dict(type='ANONYMOUS', **flow['body']))
    expected_owners = {site_key(owner['occurrence']) for owner in anonymous_owners if owner['type'] == 'ANONYMOUS'}
    # The production read retains its invocation before its anonymous owner.
    if one_result and oracle.owner == 'read':
        expected_owners = set()
        assert 'RESULT_LIMIT_REACHED' in flow['obligations'], flow
    owner_bindings = flow['owner_bindings']
    assert {site_key(owner['body']['occurrence']) for owner in owner_bindings} == expected_owners, flow
    if oracle.owner in ('nestedInline', 'unsupportedOuter', 'nestedDirectInline', 'nestedDirectOrdinary'):
        region_start = text.index('fun ' + oracle.owner + '(')
        region_end = next_function(text, region_start + len('fun ' + oracle.owner + '('))
        outer_start = text.index('{', region_start, region_end)
        outer_end = text.rfind('}', region_start, region_end) + 1
        expected_outer = (site['file'], len(text[:outer_start].encode('utf-16-le')) // 2,
                          len(text[:outer_end].encode('utf-16-le')) // 2)
        assert len(owner_bindings) == 1, owner_bindings
        assert site_key(owner_bindings[0]['body']['occurrence']) == expected_outer, owner_bindings
        outer_binding = owner_bindings[0]['binding']
        receiver = 'ordinaryInline' if oracle.owner in ('nestedInline', 'nestedDirectInline') else 'ordinaryCallback'
        assert outer_binding['type'] == 'BOUND' and outer_binding['position'] == 0, outer_binding
        assert callable_name(outer_binding['callable']) == receiver, outer_binding
        parameter_start = text.index('block: () -> String', text.index('fun ' + receiver + '(block:'))
        parameter_utf16 = len(text[:parameter_start].encode('utf-16-le')) // 2
        assert outer_binding['parameter']['range'] == dict(startInclusive=parameter_utf16,
            endExclusive=parameter_utf16 + len('block: () -> String')), outer_binding
        if oracle.owner == 'nestedDirectOrdinary':
            assert site_key(policy['excluded_boundary']) == expected_outer, policy
    for owner in owner_bindings:
        assert 'NESTED_CALLBACK_EXECUTION' in owner['obligations'], owner
        supply = owner['supply']
        if supply['type'] in ('INVOCATION', 'RETURNED'):
            occurrence = supply['occurrence']
            exact = owner['body']['occurrence']
            assert occurrence['file'] == exact['file']
            assert occurrence['range']['startInclusive'] <= exact['range']['startInclusive']
            assert exact['range']['endExclusive'] <= occurrence['range']['endExclusive']
        if primary_supply:
            assert supply['type'] == primary_supply, owner
            assert owner['binding']['type'] == 'UNAVAILABLE', owner
            if primary_supply == 'INVOCATION':
                occurrence = supply['occurrence']
                source = Path(occurrence['file']).read_text().encode('utf-16-le')
                bounds = occurrence['range']
                assert source[bounds['startInclusive'] * 2:bounds['endExclusive'] * 2].decode('utf-16-le') == '({ inlineTarget() })()'
        if oracle.owner == 'read':
            assert supply['type'] == 'INVOCATION', owner
            assert owner['binding'] == dict(type='UNAVAILABLE', cause='EXTERNAL_CALLABLE'), owner
            assert {'EXTERNAL_CALLABLE', 'OUTSIDE_DOMAIN', 'NESTED_CALLBACK_EXECUTION'} <= set(owner['obligations']), owner
            occurrence = supply['occurrence']
            source = Path(occurrence['file']).read_text().encode('utf-16-le')
            bounds = occurrence['range']
            assert source[bounds['startInclusive'] * 2:bounds['endExclusive'] * 2].decode('utf-16-le') == 'readAction { operation() }'


def qualify_value_bindings(args, budget, invoke, drain):
    """Real VALUE_PATHS requests, with source-authored destinations and immutable replay."""
    relative = 'logging/src/main/kotlin/representation/fixture/RepresentationImpactFixture.kt'
    text = (args.root / relative).read_text()

    def locate(offset):
        pages = drain(dict(verbose=True, request=dict(type='RUN',
            source=dict(type='AT_LOCATION', file=relative, offset=offset), steps=[],
            output=dict(type='SYMBOLS', fields=['NAME', 'LOCATION']), executionBudget=asdict(budget))))
        items = [item for page in pages for item in page['items']]
        assert len(items) == 1 and items[0]['type'] == 'exact-symbol', items
        return items[0]['ref']

    enclosing = locate(text.index('fun investigate') + 4)
    callable_ref = locate(text.index('fun encrypt') + 4)
    seeds = (('first', 'Voltage.encrypt(accountA)'), ('second', 'Voltage.encrypt(accountB)'))
    checked = []
    for name, expression in seeds:
        start = text.index(expression)
        assert text[start:start + len(expression)] == expression
        evaluation_budget = Budget(maxResults=1000, maxReturnedBytes=524288)
        pages = drain(dict(verbose=True, request=dict(type='RUN', source=dict(type='IMPACT',
            seeds=[dict(enclosing=enclosing, callable=callable_ref, anchor=dict(start=start, end=start + len(expression)))],
            declarations=[], models=[], domain=dict(type='WORKSPACE'), flow='KOTLIN_FORWARD_V1'), steps=[],
            output=dict(type='VALUE_PATHS'), retention='RETAIN', executionBudget=asdict(evaluation_budget))))
        paths = [item['path'] for page in pages for item in page['items'] if item['type'] == 'VALUE_PATH']
        assert paths, name
        assert all(p['producer']['range'] == dict(start=start, end=start + len(expression)) for p in paths)
        transfers = [step['transfer'] for path in paths for step in path['steps'] if step['type'] == 'COMPILER']
        assert {'LOCAL_BINDING', 'LOCAL_READ'} <= {t['kind'] for t in transfers}, name
        expected = [('submit(misleadingHipedName, second)', 0)] if name == 'first' else [
            ('submit(misleadingHipedName, second)', 1), ('display(second)', 0), ('submit(wrapped, second)', 1)]
        for destination, slot in expected:
            offset = text.index(destination)
            assert any(t['target']['role']['type'] == 'ARGUMENT'
                and t['target']['role']['index'] == slot
                and t['target']['role']['invocation']['range'] == dict(start=offset, end=offset + len(destination))
                for t in transfers), (name, destination, slot)
        if name == 'first':
            assert 'WRAPPER_RETURN' in {t['kind'] for t in transfers}, name
            assert any(p['terminal']['type'] == 'UNRESOLVED_FLOW'
                       and p['terminal']['cause'] == 'MUTABLE_CONTROL_FLOW' for p in paths), name
        retention = pages[-1]['retention']
        assert retention['kind'] == 'retained', retention
        reference, cursor, seen = retention['reference'], None, set()
        retained_items = []
        for _ in range(512):
            action = dict(type='READ_RESULT', result=reference, output=dict(type='VALUE_PATHS'),
                          executionBudget=asdict(budget))
            if cursor is not None:
                action['cursor'] = cursor
            presented = drain(dict(verbose=True, request=action))
            retained_items.extend(item for page in presented for item in page['items'])
            cursor = presented[-1].get('next_cursor')
            if cursor is None:
                break
            assert cursor not in seen, 'retained cursor repeated'
            seen.add(cursor)
        else:
            raise AssertionError('retained value paths did not drain')
        assert len(paths) <= 1 or len(seen) > 0, 'multiple paths bypassed one-result pagination'
        assert [item['path'] for item in retained_items] == paths, 'retained paths changed or duplicated'
        ids = [item['row_id'] for item in retained_items]
        assert len(ids) == len(set(ids)), 'retained row identity duplicated'
        checked.append(dict(seed=name, valuePaths=len(paths), retainedPages=len(seen) + 1))
    return checked


def run(args):
    budget = Budget(maxReturnedBytes=args.max_returned_bytes)
    output = replay.fresh(args.output)
    catalog_rpc = args.rpc.parent / 'kast-tool-rpc-complete' if args.mcp else args.rpc
    catalog = replay.capture([catalog_rpc, 'catalog'], args.root, timeout=60)
    replay.write(output / 'catalog-process.json', catalog)
    tools = json.loads(catalog['stdout'])['catalog']['tools']
    validator = jsonschema.Draft202012Validator(next(t['inputSchema'] for t in tools if t['name'] == 'query_symbols'))
    if args.mcp:
        with closing(replay.McpReplaySession(args.rpc, args.root, output, args.timeout)) as session:
            observed = session.catalog()
            assert {t['name']: t['inputSchema'] for t in observed['tools']} == {
                t['name']: t['inputSchema'] for t in tools}, 'session catalog changed'
            result_schema = next(t['outputSchema'] for t in observed['tools'] if t['name'] == 'query_symbols')
            result_validator = jsonschema.Draft202012Validator(result_schema)
            qualify(args, budget, output, validator, session, result_validator)
    else:
        qualify(args, budget, output, validator)


def qualify(args, budget, output, validator, session=None, result_validator=None):
    counter = 0
    terminal_pages = []

    def invoke(payload):
        nonlocal counter
        validator.validate(payload)
        process = session.call(payload) if session else replay.capture(
            [args.rpc, 'call', 'query_symbols'], args.root, json.dumps(payload), args.timeout)
        envelope = json.loads(process['stdout']) if process['outcome'] == 'completed' else {}
        document = envelope.get('result', {}).get('structuredContent') if session else envelope.get('document')
        call = replay.ReplayCall(payload['request']['type'], payload, process, document, [], [], 'UNAVAILABLE')
        replay.write(output / f'call-{counter:04d}.json', asdict(call))
        counter += 1
        if document is not None and result_validator is not None:
            result_validator.validate(document)
        return call

    def drain(payload):
        calls = replay.drain_workload(payload, invoke, 512)
        assert calls and all(c.response is not None for c in calls), 'missing semantic response'
        pages = [c.response for c in calls]
        for call in calls:
            limit = call.request['request'].get('executionBudget', asdict(budget))['maxResults']
            assert len(call.response.get('items', [])) <= limit, 'page exceeds requested result grant'
            byte_limit = call.request['request'].get('executionBudget', asdict(budget))['maxReturnedBytes']
            effective_bytes = call.response.get('execution_budget', {}).get('max_returned_bytes', {}).get('effective', byte_limit)
            assert len(json.dumps(call.response, ensure_ascii=False, separators=(',', ':')).encode()) <= min(byte_limit, effective_bytes), 'page exceeds returned-byte grant'
        assert all(not page.get('failures') for page in pages), pages[-1]
        assert pages[-1].get('status') in ('complete', 'qualified'), pages[-1]
        assert replay.continuation(pages[-1]) is None, 'drain stopped with continuation'
        assert pages[-1]['status'] != 'rejected', pages[-1]
        assert len({json.dumps(p.get('live'), sort_keys=True) for p in pages}) == 1, 'basis moved'
        terminal_pages.append(dict(status=pages[-1]['status'], coverage=pages[-1].get('coverage'),
                                   terminalReason=pages[-1].get('terminal_reason'),
                                   qualification=pages[-1].get('qualification')))
        return pages

    inspected = set()

    def inspect_source(site, basis=None, live=None):
        candidate = site['candidateSelector']
        if candidate in inspected:
            return
        call = invoke(dict(verbose=True, request=dict(type='READ_SOURCE', candidateRef=candidate,
                                                     executionBudget=asdict(budget))))
        document = call.response
        assert document and document['operation'] == 'source.read', document
        assert document['status'] == 'complete', document
        assert document['snapshot']['file'] == site['file'], document
        if basis is not None:
            assert basis['type'] == 'LIVE', basis
            live = dict(root=basis['root'], host=basis['host'], epoch=basis['epoch'],
                        contentView=basis['contentView'], version=basis['referenceVersion'])
        assert live and 'host' in live and 'epoch' in live and 'version' in live, live
        assert document['snapshot']['live'] == live, document
        assert document['region']['selection']['range'] == site['range'], document
        assert document['text']['type'] == 'returned', document
        bounds = site['range']
        expected = Path(site['file']).read_text().encode('utf-16-le')[
            bounds['startInclusive'] * 2:bounds['endExclusive'] * 2].decode('utf-16-le')
        assert document['text']['text'] == expected, document
        inspected.add(candidate)

    def inspect_observation(observation):
        basis = observation['flow']['basis']
        for site in (observation['occurrence'], observation['callback_body'],
                     observation['target']['declaration'], observation['lexical_owner']['declaration']):
            inspect_source(site, basis)
        binding = observation['flow']['binding']
        if binding['type'] == 'BOUND':
            for site in (binding['parameter'], binding['invocation_occurrence'], binding['callable']['declaration']):
                inspect_source(site, basis)
            owner = binding['invocation_owner']
            inspect_source(owner['occurrence'] if owner['type'] == 'ANONYMOUS' else owner['callable']['declaration'], basis)
        if binding['type'] == 'DEFAULT':
            for site in (binding['parameter']['parameter'], binding['parameter']['callable']['declaration'],
                         binding['default_value']):
                inspect_source(site, basis)
        if binding['type'] == 'DIRECT':
            inspect_source(binding['occurrence'], basis)
            owner = binding['owner']
            inspect_source(owner['occurrence'] if owner['type'] == 'ANONYMOUS' else owner['callable']['declaration'], basis)
        for invocation in observation['flow']['invocations']:
            inspect_source(invocation['occurrence'], basis)
            owner = invocation['owner']
            inspect_source(owner['occurrence'] if owner['type'] == 'ANONYMOUS' else owner['callable']['declaration'], basis)
            for forwarding in invocation['forwardings']:
                source, argument, target = forwarding['source'], forwarding['argument'], forwarding['target']
                for site in (source['parameter'], source['callable']['declaration'], argument,
                             target['parameter'], target['callable']['declaration'], target['invocation_occurrence']):
                    inspect_source(site, basis)
        for owner in observation['flow']['owner_bindings']:
            inspect_source(owner['body']['occurrence'], basis)
            if owner['supply']['type'] in ('INVOCATION', 'RETURNED'):
                inspect_source(owner['supply']['occurrence'], basis)
            binding = owner['binding']
            if binding['type'] == 'BOUND':
                for site in (binding['parameter'], binding['invocation_occurrence'], binding['callable']['declaration']):
                    inspect_source(site, basis)
            if binding['type'] == 'DEFAULT':
                for site in (binding['parameter']['parameter'], binding['parameter']['callable']['declaration'],
                             binding['default_value']):
                    inspect_source(site, basis)
            if binding['type'] == 'DIRECT':
                inspect_source(binding['occurrence'], basis)

    if args.value_flow:
        checks = qualify_value_bindings(args, budget, invoke, drain)
        replay.write(output / 'qualification.json', dict(type='INSTALLED_VALUE_BINDINGS_QUALIFIED', calls=counter,
            root=str(args.root), rpc=str(args.rpc), rpcSha256=replay.digest(args.rpc),
            transport='MCP_SESSION' if session else 'TOOL_RPC', budget=asdict(budget), checks=checks, terminalPages=terminal_pages))
        print(output / 'qualification.json')
        return

    if args.production:
        target = 'readPrepared'
        directory = 'relation/intellij/src/main/kotlin'
        oracle = Oracle('read', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True, 'NESTED_CALLBACK_EXECUTION')
        cases = (oracle,)
    else:
        target, directory, cases = 'inlineTarget', 'logging/src/main/kotlin', FIXTURE_ORACLES
    checks = []
    matrix_budget = Budget(maxResults=1000, maxReturnedBytes=budget.maxReturnedBytes)
    groups = [(target, cases, None)]
    if not args.production:
        file, text, _, _ = authored_region(args.root, 'defaultMethodInlineDefinition')
        offset = text.index('fetch()', text.index('interface BaseClient'))
        method_source = dict(type='AT_LOCATION', file=str(file.relative_to(args.root)),
                             offset=len(text[:offset].encode('utf-16-le')) // 2)
        groups.append(('fetch', METHOD_DEFAULT_ORACLES, method_source))
        groups.append(('ignoredCallbackTarget', IGNORED_INVOKE_ORACLES, None))
    for target, cases, caller_source in groups:
        for walk in (False, True):
            caller_observations = (list(observations(drain(request(target, directory, 'CALLERS', walk, budget, caller_source))))
                                   if any(not o.exhaustive_budget for o in cases) else [])
            exhaustive_pages = (drain(request(target, directory, 'CALLERS', walk, matrix_budget, caller_source))
                                if any(o.exhaustive_budget for o in cases) else [])
            exhaustive_observations = list(observations(exhaustive_pages))
            for oracle in cases:
                case_budget = matrix_budget if oracle.exhaustive_budget else budget
                available = exhaustive_observations if oracle.exhaustive_budget else caller_observations
                matched = [o for o in available if callable_name(o['lexical_owner']) == oracle.owner]
                expected = expected_occurrences(args.root, oracle, target, args.production)
                assert Counter(site_key(o['occurrence']) for o in matched) == expected, (oracle.owner, 'CALLERS', matched, expected)
                for observation in matched:
                    check(observation, oracle, target, case_budget)
                    inspect_observation(observation)
                check_named_edge(exhaustive_pages, oracle, target)
                declaration = matched[0]['lexical_owner']['declaration']
                source = dict(type='AT_LOCATION', file=str(Path(declaration['file']).relative_to(args.root)),
                              offset=declaration['range']['startInclusive'])
                pages = drain(request(oracle.owner, directory, 'CALLEES', walk, case_budget, source))
                check_named_edge(pages, oracle, target)
                callee_observations = [o for o in observations(pages) if callable_name(o['target']) == target]
                assert Counter(site_key(o['occurrence']) for o in callee_observations) == expected, (oracle.owner, 'CALLEES', callee_observations, expected)
                for observation in callee_observations:
                    check(observation, oracle, target, case_budget)
                checks.append(dict(owner=oracle.owner, target=target, walk=walk, matchedOccurrences=len(matched),
                                   bindingCauses=[o['flow']['binding'].get('cause') for o in matched],
                                   obligations=[o['flow']['obligations'] for o in matched],
                                   budget=asdict(case_budget)))
            if not args.production:
                # Declaration-bound defaults are possible inputs to the formal parameter.
                # A direct callee read at an omitted or replaced call must not attach every
                # default body in the receiving declaration to the lexical caller.
                for owner, expression, call_target in DEFAULT_CALL_SITES:
                    if call_target != target:
                        continue
                    file, text, start, end = authored_region(args.root, owner)
                    assert text.index(expression, start, end) >= start
                    source = dict(type='AT_LOCATION', file=str(file.relative_to(args.root)),
                                  offset=len(text[:start].encode('utf-16-le')) // 2)
                    pages = drain(request(owner, directory, 'CALLEES', walk, matrix_budget, source))
                    callbacks = [o for o in observations(pages) if callable_name(o['target']) == target]
                    assert not callbacks, (owner, callbacks)
                    checks.append(dict(owner=owner, target=target, walk=walk, matchedOccurrences=0,
                                       defaultAttribution='DECLARATION_CONTEXT_REQUIRED', budget=asdict(matrix_budget)))
    if not args.production:
        file, text, _, _ = authored_region(args.root, 'ordinaryCallback')
        for walk in (False, True):
            for oracle in PARAMETER_CALLEE_ORACLES:
                marker = 'fun ' + oracle.definition
                start = text.index(marker)
                end = next_function(text, start + len(marker))
                name = oracle.definition.split('(')[0]
                source = dict(type='AT_LOCATION', file=str(file.relative_to(args.root)),
                              offset=len(text[:start].encode('utf-16-le')) // 2)
                pages = drain(request(name, directory, 'CALLEES', walk, matrix_budget, source))
                assert pages[-1]['status'] == 'complete', (oracle.definition, pages[-1])
                parameter_calls = [o for o in callable_observations(pages)
                                   if o['target']['type'] == 'PARAMETER_INVOCATION']
                assert len(parameter_calls) == 1, (oracle.definition, parameter_calls)
                observation = parameter_calls[0]
                parameter = observation['target']['parameter']
                assert callable_name(observation['lexical_owner']) == name, observation
                assert parameter['callable'] == observation['lexical_owner'], observation
                assert parameter['position'] == oracle.position, parameter
                parameter_offset = len(text[:text.index(oracle.parameter, start, end)].encode('utf-16-le')) // 2
                assert site_key(parameter['parameter']) == (str(file), parameter_offset,
                    parameter_offset + len(oracle.parameter)), parameter
                invocation_offset = len(text[:text.index(oracle.invocation, start, end)].encode('utf-16-le')) // 2
                assert site_key(observation['occurrence']) == (str(file), invocation_offset,
                    invocation_offset + len(oracle.invocation)), observation
                body = observation['body']
                assert body['type'] == ('ANONYMOUS' if oracle.anonymous_owner else 'NAMED'), body
                if oracle.anonymous_owner:
                    assert source_text(body['occurrence']) == '{ block() }', body
                else:
                    assert body['callable'] == parameter['callable'], body
                for site in (observation['occurrence'], parameter['parameter'], parameter['callable']['declaration'],
                             body['occurrence'] if oracle.anonymous_owner else body['callable']['declaration']):
                    inspect_source(site, live=pages[-1]['live'])
                checks.append(dict(owner=name, walk=walk, target='PARAMETER_INVOCATION',
                                   parameterPosition=oracle.position, matchedOccurrences=1,
                                   body=body['type'], budget=asdict(matrix_budget)))
    replay.write(output / 'qualification.json', dict(type='INSTALLED_CALLBACK_QUALIFIED', calls=counter,
        root=str(args.root), rpc=str(args.rpc), rpcSha256=replay.digest(args.rpc),
        transport='MCP_SESSION' if session else 'TOOL_RPC',
        budget=asdict(budget), matrixBudget=asdict(matrix_budget),
        inspectedSourceRanges=len(inspected), checks=checks, terminalPages=terminal_pages))
    print(output / 'qualification.json')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--rpc', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--production', action='store_true')
    parser.add_argument('--value-flow', action='store_true')
    parser.add_argument('--mcp', action='store_true', help='Reuse one installed public MCP session')
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--max-returned-bytes', type=int, default=524288)
    run(parser.parse_args())
