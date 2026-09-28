"""Installed compact source retains text and selection authority."""
from dataclasses import asdict, dataclass, replace
from hosted_source_read_regression import SourceFunctionRequest, SourceExecutionBudget, SymbolAnchor


@dataclass(frozen=True)
class CompleteText:
    type: str = 'COMPLETE'
    maxBytes: int = 65536


def _selection(value, table):
    token = table[value['id']]['selector'] if table is not None else value['selector']
    bounds = value['range']
    return token, bounds['startInclusive'], bounds['endExclusive']


def _rows(entities, table):
    result = []
    for item in entities:
        target = item.get('target', item.get('semanticIdentity'))
        target_fact = None
        if target is not None:
            token = table[target['selection']]['selector'] if 'selection' in target else target.get('selector')
            target_type = 'candidate' if item['type'] == 'declaration' else target['type']
            target_fact = (target_type, token, target.get('reason'))
        parent = table[item['parent']]['selector'] if table is not None else item['parentSelector']
        result.append((item['type'], item.get('name'), item.get('kind', '').lower(),
            item.get('visibility', '').lower(), item['nestingDepth'], parent,
            _selection(item['selection'], table),
            _selection(item['callee'], table) if 'callee' in item else None, target_fact))
    return tuple(result)


def compact_source_request(reference, budget=None):
    return SourceFunctionRequest(SymbolAnchor(reference), text=CompleteText(),
        executionBudget=budget or SourceExecutionBudget(maxElapsedMs=5000, maxWorkUnits=10000,
            maxResults=100))


def run_compact_source_regression(replay):
    request = compact_source_request(replay.seeds['logger']['ref'])
    compact = replay.transport.invoke(replay.surface, 'read_source', asdict(request))
    replay.transport.validate('read_source', compact)
    sections = compact.get('content', [])
    source = next((section for section in sections if section.get('type') == 'source'), {})
    structure = next((section for section in sections if section.get('type') == 'structure'), {})
    text, table = source.get('text', {}), structure.get('selections', [])
    checks = {
        'complete': compact.get('status') == 'complete',
        'sourceTextReturned': text.get('type') == 'returned' and isinstance(text.get('text'), str),
        'selectionTablePresent': bool(table) and all(isinstance(item.get('selector'), str) for item in table),
        'snapshotRetained': structure.get('snapshot', {}).get('live') == replay.live,
    }
    if table:
        restored = replay.transport.invoke(replay.surface, 'read_source',
            asdict(replace(request, symbolRef=SymbolAnchor(table[0]['selector']))))
        checks['exactSelectionRestores'] = (restored.get('status') == 'complete'
            and any(section.get('type') == 'source' for section in restored.get('content', [])))
    replay.record('compact-source-lossless-installed', 'read_source', checks)
