#!/usr/bin/env python3
"""Finite native refresh polling and qualification gates, without launching an IDE."""
from dataclasses import asdict, dataclass, field
from unittest.mock import patch, Mock
import unittest

from hosted_workspace_refresh_regression import RefreshEffect, RefreshRequest, await_refresh, observe_visibility, VisibilityReason
from hosted_change_acceptance import remaining_matrix_gates


@dataclass(frozen=True)
class Pending:
    requestId: str
    stage: str
    type: str = field(default='pending', init=False)


@dataclass(frozen=True)
class Complete:
    requestId: str
    type: str = field(default='complete', init=False)


@dataclass(frozen=True)
class Failed:
    requestId: str
    reason: str
    type: str = field(default='failed', init=False)


@dataclass(frozen=True)
class ClassItem:
    name: str


@dataclass(frozen=True)
class SearchResponse:
    status: str
    items: tuple[ClassItem, ...] = ()
    qualification: str | None = None
    reason: str | None = None


class NativeWorkspaceRefreshTest(unittest.TestCase):
    def test_pending_effect_and_admission_must_reach_actual_completion(self):
        request = RefreshRequest('test-request', RefreshEffect.GRADLE_MODEL_RELOAD)
        replies = [asdict(Pending(request.requestId, 'ADMISSION')), asdict(Complete(request.requestId))]
        with patch('hosted_workspace_refresh_regression.exchange_refresh', side_effect=replies) as exchange, \
                patch('hosted_workspace_refresh_regression.time.sleep'):
            self.assertEqual(2, await_refresh(None, request, asdict(Pending(request.requestId, 'EFFECT')), None))
        self.assertEqual(2, exchange.call_count)

    def test_failed_and_cancelled_import_never_count_as_complete(self):
        request = RefreshRequest('test-request', RefreshEffect.GRADLE_MODEL_RELOAD)
        for reason in ('EFFECT_FAILED', 'CANCELLED', 'NEWER_CHANGE', 'ADMISSION_REJECTED'):
            with self.assertRaisesRegex(ValueError, 'REFRESH_EFFECT_INCOMPLETE'):
                await_refresh(None, request, asdict(Failed(request.requestId, reason)), None)

    def test_pending_status_is_bounded(self):
        request = RefreshRequest('test-request', RefreshEffect.FILE_REFRESH)
        with patch('hosted_workspace_refresh_regression.time.monotonic', side_effect=[0, 2]):
            with self.assertRaisesRegex(ValueError, 'REFRESH_FIXTURE_TIMEOUT'):
                await_refresh(None, request, asdict(Pending(request.requestId, 'EFFECT')), None, timeout=1)

    def test_visibility_retains_complete_empty_and_finite_qualifications(self):
        transport = Mock()
        for response, expected, reasons in (
            (SearchResponse('complete', (ClassItem('Expected'),)), True, ()),
            (SearchResponse('complete'), False, ()),
            (SearchResponse('qualified', qualification='[time-limit, unsupported-item]'), False,
             (VisibilityReason.TIME_LIMIT, VisibilityReason.UNSUPPORTED_ITEM)),
            (SearchResponse('rejected', reason='workspace-not-ready'), False,
             (VisibilityReason.WORKSPACE_NOT_READY,)),
        ):
            transport.invoke_observed.return_value = asdict(response), 'schema'
            receipt = observe_visibility(transport, 'Expected')
            self.assertEqual(expected, receipt.visible)
            self.assertEqual(reasons, receipt.reasons)
            self.assertGreaterEqual(receipt.roundTripNanos, 0)

    def test_absent_refresh_acceptance_remains_unqualified(self):
        names = {item['scenario'] for item in remaining_matrix_gates()}
        self.assertIn('explicit-workspace-file-and-model-refresh', names)


if __name__ == '__main__':
    unittest.main()
