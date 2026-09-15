"""Installed compact/expanded source equality over an unchanged authored file."""
from dataclasses import asdict, dataclass, replace
import json
from hosted_source_read_regression import SourceFunctionRequest, SourceExecutionBudget, SymbolAnchor


@dataclass(frozen=True)
class CompleteText:
    type: str = 'complete'


@dataclass(frozen=True)
class SourceAnchor:
    selector: str
    type: str = 'source'


@dataclass(frozen=True)
class FormattedSourceRequest(SourceFunctionRequest):
    format: str = 'compact'


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
            target_fact = (target['type'], token, target.get('reason'))
        parent = table[item['parent']]['selector'] if table is not None else item['parentSelector']
        result.append((item['type'], item.get('name'), item.get('kind', '').lower(),
            item.get('visibility', '').lower(), item['nestingDepth'], parent,
            _selection(item['selection'], table),
            _selection(item['callee'], table) if 'callee' in item else None, target_fact))
    return tuple(result)


def run_compact_source_regression(replay):
    budget = SourceExecutionBudget(max_elapsed_ms=5000, max_work_units=10000, max_results=100)
    request = FormattedSourceRequest(SymbolAnchor(replay.seeds['logger']['ref']),
        text=CompleteText(), entityLimit=100, execution_budget=budget)
    expanded = replay.transport.invoke(replay.surface, 'source_read', asdict(replace(request, format='expanded')))
    compact = replay.transport.invoke(replay.surface, 'source_read', asdict(request))
    replay.transport.validate('source_read', expanded)
    replay.transport.validate('source_read', compact)
    sections = compact.get('content', [])
    shape = len(sections) == 2 and [section.get('type') for section in sections] == ['source', 'structure']
    checks = {'selectedCompactSections': shape, 'completeBoth': expanded.get('status') == compact.get('status') == 'complete'}
    if shape and checks['completeBoth']:
        text, structure = sections[0]['text'], sections[1]
        table = structure['selections']
        checks.update(
            unchangedText=text.get('type') == 'returned' and text.get('text') == expanded['text'].get('text'),
            textCoordinates=_selection(text['selection'], table) == _selection(expanded['text']['selection'], None),
            lineCoordinates=text['lines'] == expanded['text']['lines'],
            sameSnapshot=structure['snapshot'] == expanded['snapshot'],
            sameRegion=_selection(structure['region']['selection'], table) == _selection(expanded['region']['selection'], None),
            orderedEntities=_rows(structure['entities'], table) == _rows(expanded['entities'], None),
            selfContainedTable=bool(table) and len({entry['selector'] for entry in table}) == len(table),
        )
        restored = replay.transport.invoke(replay.surface, 'source_read',
            asdict(replace(request, anchor=SourceAnchor(table[0]['selector']))))
        checks['canonicalSourceSelectorRestores'] = (restored.get('status') == 'complete'
            and restored.get('content', [{}])[0].get('text', {}).get('text') == text['text'])
        # Measures CLI documents only; transport/provider envelope budgets are separate owners.
        checks['compactDocumentSmaller'] = len(json.dumps(compact, ensure_ascii=False).encode()) < len(json.dumps(expanded, ensure_ascii=False).encode())
    replay.record('compact-source-lossless-installed', 'source_read', checks)
