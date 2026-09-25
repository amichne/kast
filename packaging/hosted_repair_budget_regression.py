"""Bounded installed 10/20-second request evidence; reuse existing grants and transport."""
from dataclasses import asdict, dataclass
from enum import Enum
import json
import time

from hosted_budget_read_regression import (
    BudgetSource, BudgetRelation, BudgetTraversal,
    ElapsedBudget, independent_grant,
)
from hosted_source_read_regression import SymbolAnchor
from query_name_request import name_query
from hosted_repair_time_observation import NativeRepairTimeWindow, NativeTimeEvidence
from hosted_transport_observation import TransportSummary, TransportWitnessFailure, TransportWitnessRejected


class SemanticOutcome(str, Enum):
    COMPLETE = 'complete'
    QUALIFIED = 'qualified'
    REJECTED = 'rejected'


class TimeClamp(str, Enum):
    OPERATOR_CEILING = 'operator_ceiling'
    TRANSPORT_CAPACITY = 'transport_capacity'
    DEADLINE_REMAINING = 'deadline_remaining'


class ReceiptFailure(str, Enum):
    SURFACE = 'surface_rejected'
    REQUEST = 'request_rejected'
    GRANT = 'grant_rejected'
    AMOUNT = 'amount_rejected'
    CLAMP = 'clamp_rejected'
    OUTCOME = 'outcome_rejected'


@dataclass(frozen=True)
class RepairReceiptRejected:
    reason: ReceiptFailure


@dataclass(frozen=True)
class RepairTimeReceipt:
    surface: str
    tool: str
    requestedMillis: int
    configuredDefaultMillis: int
    operatorCeilingMillis: int
    actualGrantMillis: int
    clamping: tuple[TimeClamp, ...]
    roundTripNanos: int
    outcome: SemanticOutcome
    schemaValidated: bool
    event: str = 'kast_repair_time_request'


@dataclass(frozen=True)
class RepairNativeTimeReceipt:
    surface: str
    tool: str
    requestedMillis: int
    host: NativeTimeEvidence
    transport: TransportSummary
    event: str = 'kast_repair_native_time'


@dataclass(frozen=True)
class RepairNativeTimeRejected:
    surface: str
    tool: str
    requestedMillis: int
    failure: TransportWitnessFailure
    event: str = 'kast_repair_native_time_rejected'


def admit_time_receipt(surface, tool, requested, response, elapsed):
    """Transport validates the full matched schema before this narrow receipt projection."""
    if surface not in ('cli', 'provider') or tool not in (
            'query_symbols', 'source_read', 'read_relations', 'traverse_relations'):
        return RepairReceiptRejected(ReceiptFailure.SURFACE)
    if requested not in (10000, 20000) or type(elapsed) is not int or elapsed < 0:
        return RepairReceiptRejected(ReceiptFailure.REQUEST)
    if not independent_grant(response, ElapsedBudget(requested)):
        return RepairReceiptRejected(ReceiptFailure.GRANT)
    grant = response['execution_budget']['max_elapsed_ms']
    amounts = tuple(grant[key] for key in ('configuredDefault', 'operatorCeiling', 'effective'))
    if any(type(value) is not int or not 0 < value <= 2**63 - 1 for value in amounts):
        return RepairReceiptRejected(ReceiptFailure.AMOUNT)
    if any(value not in {item.value for item in TimeClamp} for value in grant['clamping']):
        return RepairReceiptRejected(ReceiptFailure.CLAMP)
    clamps = tuple(TimeClamp(value) for value in grant['clamping'])
    if len(clamps) > 3 or len(set(clamps)) != len(clamps):
        return RepairReceiptRejected(ReceiptFailure.CLAMP)
    if response.get('status') not in {item.value for item in SemanticOutcome}:
        return RepairReceiptRejected(ReceiptFailure.OUTCOME)
    return RepairTimeReceipt(surface, tool, requested, *amounts, clamps, elapsed,
                             SemanticOutcome(response['status']), True)


def run_repair_time_regression(replay):
    receipts = []
    native_receipts = []
    for millis in (10000, 20000):
        budget = ElapsedBudget(millis)
        cases = (
            ('query_symbols', name_query('pageItem00', ('function',), budget=budget)),
            ('source_read', BudgetSource(SymbolAnchor(replay.seeds['logger']['ref']), budget)),
            ('read_relations', BudgetRelation(replay.seeds['helper']['ref'], budget)),
            ('traverse_relations', BudgetTraversal(replay.seeds['helper']['ref'], budget)),
        )
        for tool, request in cases:
            with NativeRepairTimeWindow(replay.transport.isolation.root / 'ide/log/idea.log', replay.live) as window:
                started = time.monotonic_ns()
                response = replay.transport.invoke(replay.surface, tool, asdict(request))
                elapsed = time.monotonic_ns() - started
                try:
                    host, transport = window.completed_timing()
                    native = RepairNativeTimeReceipt(replay.surface, tool, millis, host, transport)
                except TransportWitnessRejected as failure:
                    native = RepairNativeTimeRejected(replay.surface, tool, millis, failure.failure)
            replay.transport.validate(tool, response)
            receipt = admit_time_receipt(replay.surface, tool, millis, response, elapsed)
            receipts.append(receipt)
            native_receipts.append(native)
            # Separate bounded receipts preserve actual amounts without logging source or live tokens.
            print(json.dumps(asdict(receipt), separators=(',', ':')), flush=True)
            print(json.dumps(asdict(native), separators=(',', ':')), flush=True)
    replay.record('repair-ten-twenty-second-requests', 'all', {
        'eightSchemaValidatedGrants': len(receipts) == 8 and all(isinstance(item, RepairTimeReceipt) for item in receipts),
        'semanticOutcomes': all(isinstance(item, RepairTimeReceipt) and item.outcome != SemanticOutcome.REJECTED for item in receipts),
        'nativeAdmissionSemanticAndReserveObserved': all(isinstance(item, RepairNativeTimeReceipt) for item in native_receipts),
        'nativeGrantMatchesResponse': all(isinstance(native, RepairNativeTimeReceipt)
            and isinstance(projected, RepairTimeReceipt) and native.host.semanticMillis == projected.actualGrantMillis
            for native, projected in zip(native_receipts, receipts)),
    })
