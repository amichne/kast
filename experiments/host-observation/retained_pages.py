"""Pure admission of one producer's retained row/evidence presentation chain."""
from dataclasses import dataclass
from enum import Enum
import re


class PresentationFailure(str, Enum):
    INCOMPLETE = 'RETAINED_PRESENTATION_INCOMPLETE'
    OWNER = 'RETAINED_PRESENTATION_OWNER_CHANGED_OR_UNAVAILABLE'
    LIVE = 'RETAINED_PRESENTATION_LIVE_CHANGED_OR_UNAVAILABLE'
    ROWS = 'RETAINED_PRESENTATION_ROWS_INVALID'
    ROW_CURSOR = 'RETAINED_PRESENTATION_ROW_CURSOR_INVALID'
    EVIDENCE = 'RETAINED_PRESENTATION_EVIDENCE_WINDOW_INVALID'
    ROW_COUNT = 'RETAINED_PRESENTATION_ROW_COUNT_MISMATCH'
    UNREAD = 'RETAINED_PRESENTATION_PAGES_UNREAD'
    REQUEST = 'RETAINED_PRESENTATION_REQUEST_CHANGED'


@dataclass(frozen=True)
class PresentationComplete:
    pass


@dataclass(frozen=True)
class RetainedResultIdentity:
    value: str


@dataclass(frozen=True)
class ResultRowCursor:
    value: int


@dataclass(frozen=True)
class ResultEvidenceCursor:
    value: int


@dataclass(frozen=True)
class PresentationNext:
    result: RetainedResultIdentity
    cursor: ResultRowCursor
    evidence_cursor: ResultEvidenceCursor


@dataclass(frozen=True)
class PresentationRejected:
    failure: PresentationFailure


def cursor(value):
    return type(value) is int and 0 <= value <= 1_000_000


def presentation(calls):
    """Retain both independent cursor proofs; never promote a retained rejection preview."""
    if not calls or not isinstance(calls[-1].response, dict):
        return PresentationRejected(PresentationFailure.INCOMPLETE)
    anchor = next((i for i in range(len(calls) - 1, -1, -1)
                   if calls[i].request.get('request', {}).get('type') != 'READ_RESULT'), None)
    if anchor is None:
        return PresentationRejected(PresentationFailure.OWNER)
    first = calls[anchor].response
    if not isinstance(first, dict):
        return PresentationRejected(PresentationFailure.INCOMPLETE)
    window, invocation = first.get('evidence_window'), first.get('invocation')
    if window is not None and not isinstance(window, dict):
        return PresentationRejected(PresentationFailure.EVIDENCE)
    if invocation is not None and not isinstance(invocation, dict):
        return PresentationRejected(PresentationFailure.ROW_COUNT)
    preview = invocation.get('preview') if invocation is not None else None
    if preview is not None and not isinstance(preview, dict):
        return PresentationRejected(PresentationFailure.ROW_COUNT)
    if preview is not None and preview.get('type') not in ('INLINE', 'PREFIX'):
        return PresentationRejected(PresentationFailure.ROW_COUNT)
    if window is not None and window.get('type') not in ('FINAL', 'MORE'):
        return PresentationRejected(PresentationFailure.EVIDENCE)
    has_pages = (anchor < len(calls) - 1 or first.get('next_cursor') is not None or
                 (window is not None and window.get('type') == 'MORE') or
                 (preview is not None and preview.get('type') == 'PREFIX'))
    if not has_pages:
        return PresentationComplete()
    owner = first.get('retention', {})
    if not isinstance(owner, dict):
        return PresentationRejected(PresentationFailure.OWNER)
    reference = owner.get('reference')
    if (owner.get('kind') != 'retained' or not isinstance(reference, str) or
            not re.fullmatch(r'result:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', reference)):
        return PresentationRejected(PresentationFailure.OWNER)
    live = first.get('live')
    if not isinstance(live, dict) or not live:
        return PresentationRejected(PresentationFailure.LIVE)
    prior_rows = [c.response.get('items', []) for c in calls[:anchor]
                  if isinstance(c.response, dict)]
    if not all(isinstance(items, list) for items in prior_rows):
        return PresentationRejected(PresentationFailure.ROWS)
    row_offset = sum(map(len, prior_rows))
    evidence_offset, evidence_total, row_ids = 0, None, set()
    declared_total = invocation.get('accumulated_row_count') if invocation is not None else None
    if declared_total is not None and (type(declared_total) is not int or declared_total < 0):
        return PresentationRejected(PresentationFailure.ROW_COUNT)
    for index in range(anchor, len(calls)):
        call, response = calls[index], calls[index].response
        if (call.process.get('outcome') != 'completed' or call.process.get('exitCode') != 0 or
                not isinstance(response, dict) or response.get('status') != 'complete' or
                response.get('coverage', {}).get('exhaustive') is not True):
            return PresentationRejected(PresentationFailure.INCOMPLETE)
        if response.get('retention') != owner:
            return PresentationRejected(PresentationFailure.OWNER)
        if response.get('live') != live:
            return PresentationRejected(PresentationFailure.LIVE)
        if index > anchor:
            request = call.request['request']
            original = calls[0].request.get('request', {})
            if (request.get('executionBudget') != original.get('executionBudget') or
                    request.get('output') != original.get('output')):
                return PresentationRejected(PresentationFailure.REQUEST)
            if request.get('result') != reference:
                return PresentationRejected(PresentationFailure.OWNER)
            if (not cursor(request.get('cursor')) or not cursor(request.get('evidence_cursor')) or
                    request['cursor'] != row_offset or request['evidence_cursor'] != evidence_offset):
                return PresentationRejected(PresentationFailure.ROW_CURSOR)
        items = response.get('items')
        if not isinstance(items, list):
            return PresentationRejected(PresentationFailure.ROWS)
        for item in items:
            identity = item.get('row_id') if isinstance(item, dict) else None
            if (not isinstance(identity, str) or identity in row_ids or
                    not re.fullmatch(r'result-row:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', identity)):
                return PresentationRejected(PresentationFailure.ROWS)
            row_ids.add(identity)
        row_offset += len(items)
        next_row = response.get('next_cursor')
        if next_row is not None and (not cursor(next_row) or next_row != row_offset or not items):
            return PresentationRejected(PresentationFailure.ROW_CURSOR)
        window = response.get('evidence_window')
        if (not isinstance(window, dict) or set(window) != {'type', 'start', 'end', 'total'} or
                not all(cursor(window.get(k)) for k in ('start', 'end', 'total')) or
                not window['start'] == evidence_offset <= window['end'] <= window['total'] or
                window['type'] != ('FINAL' if window['end'] == window['total'] else 'MORE') or
                (evidence_total is not None and window['total'] != evidence_total)):
            return PresentationRejected(PresentationFailure.EVIDENCE)
        more_evidence = window['type'] == 'MORE'
        if more_evidence and window['end'] == evidence_offset:
            return PresentationRejected(PresentationFailure.EVIDENCE)
        evidence_offset, evidence_total = window['end'], window['total']
        if index < len(calls) - 1 and next_row is None and not more_evidence:
            return PresentationRejected(PresentationFailure.ROW_CURSOR)
    if next_row is not None or more_evidence:
        return PresentationNext(RetainedResultIdentity(reference), ResultRowCursor(row_offset),
                                ResultEvidenceCursor(evidence_offset))
    if declared_total is not None and row_offset != declared_total:
        return PresentationRejected(PresentationFailure.ROW_COUNT)
    return PresentationComplete()
