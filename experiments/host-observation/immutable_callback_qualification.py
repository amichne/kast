"""Independent callback-target acceptance, separate from transport/schema admission.

A complete status without callback evidence is insufficient. This reader validates
observed value/invocation graphs before comparing their target inventory with the
authored fixture. It does not execute queries or manufacture native evidence.
"""
from dataclasses import dataclass
from enum import Enum

from immutable_callback_oracle import Complete, Rejected


class QualificationFailure(str, Enum):
    INCOMPLETE = 'INCOMPLETE'
    MISSING_EVIDENCE = 'MISSING_EVIDENCE'
    UNPROVEN_FLOW = 'UNPROVEN_FLOW'
    UNKNOWN_FLOW = 'UNKNOWN_FLOW'
    TARGET_INVENTORY = 'TARGET_INVENTORY'
    DISCONNECTED_TRANSFER = 'DISCONNECTED_TRANSFER'
    SUPPLIER_INVENTORY = 'SUPPLIER_INVENTORY'


class ProofRejected(ValueError):
    def __init__(self, cause):
        self.cause = cause
        super().__init__(cause.value)


@dataclass(frozen=True)
class CallbackTargets:
    targets: tuple[str, ...]
    observations: int
    supplier_selections: tuple[str, ...]
    supplier_owners: tuple[str, ...]


def require(value, cause):
    if not value:
        raise ProofRejected(cause)


def target_name(callable):
    identity = callable['compiler_target']['compiler_evidence']['signature']['qualifiedIdentity']
    require(identity.startswith('fixture.immutable.'), QualificationFailure.TARGET_INVENTORY)
    # Retain class ownership for Receiver.member; top-level targets have no owner.
    return identity.split('.', 3)[-1]


def value_proof(value):
    factories = value['factories']
    require(len(factories) <= 1000, QualificationFailure.UNPROVEN_FLOW)
    reached = set()
    def node(current_value, limit, depth):
        require(depth <= 64, QualificationFailure.UNPROVEN_FLOW)
        current = current_value['source']
        for transfer in current_value['transfers']:
            require(transfer['source'] == current, QualificationFailure.DISCONNECTED_TRANSFER)
            current = transfer['target']
        require(current == current_value['destination'], QualificationFailure.DISCONNECTED_TRANSFER)
        origin = current_value['origin']
        if origin['type'] == 'NAMED': return (target_name(origin['target']),)
        if origin['type'] == 'ANONYMOUS': return ()
        require(origin['type'] == 'RETURNED', QualificationFailure.UNKNOWN_FLOW)
        index = origin['factory_index']
        require(type(index) is int and 0 <= index < limit, QualificationFailure.UNPROVEN_FLOW)
        reached.add(index)
        factory = factories[index]
        result = list(node(factory['returned_value'], index, depth + 1))
        inventory = factory.get('body_calls')
        require(isinstance(inventory, dict), QualificationFailure.MISSING_EVIDENCE)
        returned_origin = factory['returned_value']['origin']
        if returned_origin['type'] == 'ANONYMOUS':
            require(inventory.get('type') == 'EXHAUSTIVE' and inventory.get('body') == returned_origin['body'], QualificationFailure.UNPROVEN_FLOW)
            calls = inventory['calls']
            body = inventory['body']['occurrence']
            expected_captures = [(invocation, {key: capture['binding'][key] for key in ('callable', 'parameter', 'position')})
                for capture in factory['captures'] if capture['content']['type'] == 'CALLABLE'
                for invocation in capture['content']['invocations']]
            actual_captures, sites = [], []
            for call in calls:
                kind = call['type']
                occurrence = call['invocation']['occurrence'] if kind == 'CAPTURED' else call['occurrence']
                require(occurrence['file'] == body['file'] and
                        body['range']['startInclusive'] <= occurrence['range']['startInclusive'] <
                        occurrence['range']['endExclusive'] <= body['range']['endExclusive'], QualificationFailure.UNPROVEN_FLOW)
                require(occurrence not in sites, QualificationFailure.UNPROVEN_FLOW)
                sites.append(occurrence)
                if kind == 'NAMED': result.append(target_name(call['target']))
                elif kind == 'CAPTURED':
                    require(call['invocation']['owner'] == inventory['body'], QualificationFailure.UNPROVEN_FLOW)
                    actual_captures.append((call['invocation'], call['formal']))
                elif kind == 'BOUNDARY':
                    require((call['callable']['module_kind'], call['disposition']) in
                            (('BUILTINS', 'BUILTIN_BOUNDARY'), ('LIBRARY', 'LIBRARY_POLICY_EXCLUDED')), QualificationFailure.UNPROVEN_FLOW)
                else: raise ProofRejected(QualificationFailure.UNKNOWN_FLOW)
            require(len(actual_captures) == len(expected_captures) and all(item in expected_captures for item in actual_captures), QualificationFailure.UNPROVEN_FLOW)
        else:
            require(inventory == {'type': 'NOT_APPLICABLE'}, QualificationFailure.UNPROVEN_FLOW)
        for capture in factory['captures']:
            content = capture['content']
            if content['type'] == 'SCALAR': continue
            require(content['type'] == 'CALLABLE' and content['values'], QualificationFailure.UNKNOWN_FLOW)
            names = [name for captured in content['values'] for name in node(captured, index, depth + 1)]
            if content['invocations']: result.extend(names)
        return tuple(result)
    result = node(value, len(factories), 0)
    require(reached == set(range(len(factories))), QualificationFailure.UNPROVEN_FLOW)
    return result


def complete_graph(graph):
    require(graph['type'] == 'EXHAUSTED_GRAPH', QualificationFailure.UNPROVEN_FLOW)
    require(graph['root'] in graph['formals'], QualificationFailure.UNPROVEN_FLOW)
    require(all(item not in graph['formals'][:index] for index, item in enumerate(graph['formals'])), QualificationFailure.UNPROVEN_FLOW)
    for edge in graph['forwardings']:
        require(edge['source'] in graph['formals'], QualificationFailure.UNPROVEN_FLOW)
        target = edge['target']
        require(any(all(target[key] == formal[key] for key in ('callable', 'position', 'parameter'))
                    for formal in graph['formals']), QualificationFailure.UNPROVEN_FLOW)


def observed_targets(pages):
    targets, selections, owners, count = [], [], [], 0
    require(pages and all(page.get('status') == 'complete' for page in pages), QualificationFailure.INCOMPLETE)
    anonymous_calls = [callback for page in pages for group in page['relation_observations']
                       for callback in group['callback_observations']]

    def supplied(use, anonymous_target):
        supplier = use['supplier']
        owners.append(target_name(supplier['binding']['invocation_owner']['callable']))
        selections.append(supplier['selection']['type'])
        require(selections[-1] in ('EXPLICIT', 'DEFAULT'), QualificationFailure.UNKNOWN_FLOW)
        names = value_proof(supplier['value']) or ((anonymous_target,) if anonymous_target is not None else ())
        origin = supplier['value']['origin']
        if not names and origin['type'] == 'ANONYMOUS':
            names = tuple(target_name(callback['target']) for callback in anonymous_calls
                          if callback.get('callback_body') == origin['body']['occurrence'])
        complete_graph(use['forwarding'])
        if use['invocations']:
            require(names, QualificationFailure.MISSING_EVIDENCE)
            targets.extend(names)

    def flow(proof, anonymous_target=None, reference_target=None):
        kind = proof['type']
        if kind == 'IMMUTABLE':
            body = proof['flow']
            require(body['scan'] == 'EXHAUSTIVE' and not body['obligations'], QualificationFailure.UNPROVEN_FLOW)
            value_proof(body['source_value'])
            require(body['uses'], QualificationFailure.MISSING_EVIDENCE)
            for use in body['uses']:
                if use['type'] == 'SUPPLIED': supplied(use, anonymous_target)
                elif use['type'] == 'DIRECT':
                    names = value_proof(use['value']) or ((anonymous_target,) if anonymous_target is not None else ())
                    require(names and use['binding']['type'] == 'DIRECT', QualificationFailure.UNPROVEN_FLOW)
                    targets.extend(names)
                elif use['type'] == 'UNUSED': value_proof(use['value'])
                else: raise ProofRejected(QualificationFailure.UNKNOWN_FLOW)
        elif kind in ('SUPPLIED', 'OBSERVED'):
            if kind == 'OBSERVED':
                require(proof['scan'] == 'EXHAUSTIVE' and not proof['obligations'], QualificationFailure.UNPROVEN_FLOW)
            complete_graph(proof['forwarding'])
            if proof['invocations']:
                name = anonymous_target or reference_target
                require(name is not None, QualificationFailure.MISSING_EVIDENCE)
                targets.append(name)
        elif kind == 'DIRECT':
            require(reference_target is not None, QualificationFailure.MISSING_EVIDENCE)
            targets.append(reference_target)
        else: raise ProofRejected(QualificationFailure.UNKNOWN_FLOW)

    for page in pages:
        require(page.get('status') == 'complete' and page.get('coverage', {}).get('exhaustive'), QualificationFailure.INCOMPLETE)
        for group in page['relation_observations']:
            for callback in group['callback_observations']:
                count += 1
                flow(callback['flow'], anonymous_target=target_name(callback['target']))
            for callback in group['callable_observations']:
                count += 1
                target = callback['target']
                if target['type'] == 'NAMED_REFERENCE':
                    reference = target['reference']
                    flow(reference['flow'], reference_target=target_name(reference['target']))
                elif target['type'] == 'PARAMETER_INVOCATION':
                    invocation = target['invocation']
                    require(invocation['occurrence'] == callback['occurrence'] and invocation['owner'] == callback['body'], QualificationFailure.UNPROVEN_FLOW)
                    route = invocation['callable_transfers']
                    require(all(edge['target'] == route[index + 1]['source'] for index, edge in enumerate(route[:-1])), QualificationFailure.DISCONNECTED_TRANSFER)
                    inventory = target['suppliers']
                    require(inventory['type'] == 'EXHAUSTIVE', QualificationFailure.SUPPLIER_INVENTORY)
                    require(inventory['root'] == target['parameter'], QualificationFailure.SUPPLIER_INVENTORY)
                    require(inventory['partitions'], QualificationFailure.SUPPLIER_INVENTORY)
                    for partition in inventory['partitions']:
                        for supplier in partition['suppliers']:
                            owners.append(target_name(supplier['binding']['invocation_owner']['callable']))
                            selections.append(supplier['selection']['type'])
                            names = value_proof(supplier['value'])
                            require(names, QualificationFailure.MISSING_EVIDENCE)
                            targets.extend(names)
                elif target['type'] == 'CALLBACK_SUPPLIES':
                    supplies, formals = target['supplies'], target['formals']
                    require(supplies and formals, QualificationFailure.SUPPLIER_INVENTORY)
                    require(all(formal not in formals[:index] for index, formal in enumerate(formals)), QualificationFailure.SUPPLIER_INVENTORY)
                    supplied_formals = [use['supplier']['binding'] for use in supplies]
                    require(all(any(all(binding[key] == formal[key] for key in ('callable', 'position', 'parameter'))
                                    for binding in supplied_formals) for formal in formals), QualificationFailure.SUPPLIER_INVENTORY)
                    require(all(any(all(binding[key] == formal[key] for key in ('callable', 'position', 'parameter'))
                                    for formal in formals) for binding in supplied_formals), QualificationFailure.SUPPLIER_INVENTORY)
                    require(all('type' not in use for use in supplies), QualificationFailure.UNKNOWN_FLOW)
                    for use in supplies: supplied(use, None)
                elif target['type'] == 'DIRECT_INVOCATIONS':
                    require(target['invocations'], QualificationFailure.MISSING_EVIDENCE)
                    for invocation in target['invocations']:
                        binding = invocation['binding']
                        require('type' not in invocation and binding['type'] == 'DIRECT', QualificationFailure.UNKNOWN_FLOW)
                        require(binding['occurrence'] == callback['occurrence'] and binding['owner'] == callback['body'], QualificationFailure.UNPROVEN_FLOW)
                        names = value_proof(invocation['value'])
                        require(names, QualificationFailure.MISSING_EVIDENCE)
                        targets.extend(names)
                else: raise ProofRejected(QualificationFailure.UNKNOWN_FLOW)
    require(count > 0, QualificationFailure.MISSING_EVIDENCE)
    return CallbackTargets(tuple(sorted(set(targets))), count, tuple(sorted(selections)), tuple(sorted(owners)))


def qualify_case(case, pages, root=None):
    if isinstance(case.expected, Rejected):
        raise ValueError('typed negative controls require their exact rejection-specific validator')
    result = observed_targets(pages)
    expected = case.expected.targets if isinstance(case.expected, Complete) else ()
    require(result.targets == tuple(sorted(expected)), QualificationFailure.TARGET_INVENTORY)
    if case.name in ('formal-supplier-inventory', 'formal-empty-inventory'):
        formal_observations = [observation['target'] for page in pages for group in page['relation_observations']
            for observation in group['callable_observations'] if observation['target']['type'] == 'PARAMETER_INVOCATION']
        require(len(formal_observations) == 1, QualificationFailure.SUPPLIER_INVENTORY)
        inventory = formal_observations[0]['suppliers']
        require(len(inventory['partitions']) == 1, QualificationFailure.SUPPLIER_INVENTORY)
        partition = inventory['partitions'][0]
        require(partition['formal'] == inventory['root'] and not partition['incoming'], QualificationFailure.SUPPLIER_INVENTORY)
    if case.name == 'formal-supplier-inventory':
        require(result.observations == 1 and result.supplier_owners == ('knownAlphaSupplier', 'knownBetaSupplier'), QualificationFailure.SUPPLIER_INVENTORY)
    if case.name == 'formal-empty-inventory':
        require(result.observations == 1 and not result.supplier_owners, QualificationFailure.SUPPLIER_INVENTORY)
    if case.name == 'default-omission':
        require(result.supplier_selections and set(result.supplier_selections) == {'DEFAULT'}, QualificationFailure.SUPPLIER_INVENTORY)
    if case.name == 'default-override':
        require('DEFAULT' not in result.supplier_selections, QualificationFailure.SUPPLIER_INVENTORY)
    if case.name in ('capture-alpha', 'capture-beta', 'captured-alias', 'branch-alternatives',
                     'direct-returned-invocation', 'local-returned-invocation'):
        require(root is not None, QualificationFailure.MISSING_EVIDENCE)
        from immutable_factory_qualification import assert_factory_contexts
        assert_factory_contexts(root, case, pages)
    if case.name == 'identity-return':
        require(root is not None, QualificationFailure.MISSING_EVIDENCE)
        from immutable_factory_qualification import assert_transparent_return
        assert_transparent_return(root, pages)
    if case.name in ('direct-try', 'local-try', 'nested-try', 'factory-try'):
        require(root is not None, QualificationFailure.MISSING_EVIDENCE)
        from immutable_try_qualification import assert_try_witnesses
        assert_try_witnesses(root, case, pages)
    return result
