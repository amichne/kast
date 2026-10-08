"""Source-authored normal-completion witnesses; no Kotlin parser or alternate solver."""
from dataclasses import dataclass
from pathlib import Path

import immutable_callback_oracle as fixture
import static_callback_oracle as source


@dataclass(frozen=True)
class TryBranch:
    enclosing: source.SourceSpan
    branch: source.SourceSpan
    catch_index: int | None

    def evidence(self):
        def range(span):
            return {'start': span.range.startInclusive, 'end': span.range.endExclusive}
        return {'type': 'NORMAL_BRANCH_RESULT', 'try_range': range(self.enclosing),
                'branch_range': range(self.branch),
                'alternative': {'type': 'TRY_BODY'} if self.catch_index is None else
                    {'type': 'CATCH_BODY', 'index': self.catch_index},
                'condition': 'NORMAL_COMPLETION'}


def authored_branches(root, case):
    root = Path(root).resolve(strict=True)
    documents = {document.relative_path: document for document in fixture.load_sources(root)}
    file = fixture.FORWARDING if case.name == 'factory-try' else fixture.SUPPLIERS
    anchor = ('fun tryChoiceFactory(): () -> String = try { ::alphaTarget } catch(e: Exception) { ::betaTarget }'
              if case.name == 'factory-try' else case.anchor)
    def span(text, within=anchor):
        return source._span(str(root), documents[file], text, within)
    def child(parent, text):
        offset = source._unique_start(parent.text, text)
        start = parent.range.startInclusive + source._utf16_length(parent.text[:offset])
        return source.SourceSpan(parent.file, source.Utf16Range(start, start + source._utf16_length(text)), text)
    if case.name in ('direct-try' , 'local-try', 'factory-try'):
        expression = span('try { ::alphaTarget } catch(e: Exception) { ::betaTarget }')
        return (TryBranch(expression, span('{ ::alphaTarget }'), None),
                TryBranch(expression, span('{ ::betaTarget }'), 0))
    if case.name == 'nested-try':
        inner_text = 'try { ::alphaTarget } catch(e: IllegalStateException) { ::betaTarget }'
        outer = span(case.anchor)
        inner = span(inner_text)
        return (TryBranch(inner, span('{ ::alphaTarget }', inner_text), None),
                TryBranch(inner, span('{ ::betaTarget }', inner_text), 0),
                TryBranch(outer, span('{ ' + inner_text + ' }'), None),
                TryBranch(outer, child(child(outer, 'catch(e: Exception) { ::betaTarget }'), '{ ::betaTarget }'), 0))
    raise ValueError('no authored try witness oracle: ' + case.name)


def assert_try_witnesses(root, case, pages):
    expected = authored_branches(root, case)
    transfers = []
    def visit(value):
        if isinstance(value, dict):
            if value.get('kind') == 'BRANCH_ALTERNATIVE' and 'source' in value and 'target' in value:
                transfers.append(value)
            for child in value.values(): visit(child)
        elif isinstance(value, list):
            for child in value: visit(child)
    for page in pages: visit(page['relation_observations'])
    for branch in expected:
        matching = [transfer for transfer in transfers if transfer.get('evidence') == branch.evidence()]
        assert matching, 'authored normal try/catch witness missing'
        for transfer in matching:
            target, origin = transfer['target'], transfer['source']
            assert target['enclosing']['file'] == origin['enclosing']['file'] == branch.enclosing.file
            assert target['range'] == branch.evidence()['try_range']
            bounds = branch.branch.range
            assert bounds.startInclusive <= origin['range']['start'] < origin['range']['end'] <= bounds.endExclusive
