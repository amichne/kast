"""Preferred and compatibility inputs share the installed provider's canonical read owners."""
from dataclasses import asdict

from hosted_budget_read_regression import BudgetRelation, BudgetTraversal, ElapsedBudget, graph_records


def run_read_name_regression(replay):
    # CLI command identities never changed; aliases are provider input and configuration spellings.
    if replay.surface != 'provider':
        return
    token = replay.seeds['helper']['ref']
    for preferred, legacy, operation, request, field in (
        ('read_relations', 'semantic_query', 'relation.read', BudgetRelation(token, ElapsedBudget()), 'relations'),
        ('traverse_relations', 'impact_analyze', 'traversal.run', BudgetTraversal(token, ElapsedBudget()), 'graph'),
    ):
        current, current_schema = replay.transport.invoke_observed('provider', preferred, asdict(request))
        previous, previous_schema = replay.transport.invoke_observed('provider', legacy, asdict(request))
        current_values = graph_records(current) if field == 'graph' else current.get(field)
        previous_values = graph_records(previous) if field == 'graph' else previous.get(field)
        checks = {
            'bothComplete': current.get('status') == previous.get('status') == 'complete',
            'sameCanonicalOperation': current.get('operation') == previous.get('operation') == operation,
            'sameLiveAuthority': current.get('live') == previous.get('live') == replay.live,
            'sameQualifiedOutputSchema': bool(current_schema) and current_schema == previous_schema,
            'sameOrderedFactsAndProofs': bool(current_values) and current_values == previous_values,
        }
        replay.record('compatibility-input-' + preferred, preferred, checks, len(current_values or ()), current)
