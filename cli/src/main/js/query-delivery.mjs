/** Portable output delivery. Canonical replies stay opaque, schema-validated RPC documents. */
const QUERY_DELIVERY_GUIDANCE = " The client owns bounded query output delivery. For query_delivery, combine initial items with ordered pages; a rejection preview is not an extra page. DELIVERED accounts only for output: initial still owns semantic completion, qualifications and rejection. Report terminal delivery blockers honestly. No continuation-management calls are needed for a delivered reply.";
const QUERY_DELIVERY_LIMITS = Object.freeze({ pages: 64, bytes: 262_144 });
const deliveryObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const deliveryInteger = value => Number.isSafeInteger(value) && value >= 0 && value <= 1_000_000;
const deliveryReference = value => typeof value === 'string' && /^result:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);
const deliveryContinuation = value => typeof value === 'string' && /^query-output:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);

/**
 * Invoke once. Only READ_RESULT and output-only RESUME may follow the admitted call.
 * No transport retry is safe without a supported reattachment identity, even for a lost initial reply.
 * Limits bound the aggregate model presentation, in addition to each physical transport response.
 */
async function awaitQueryDelivery(params, invoke, policy, signal, now = Date.now) {
  params = JSON.parse(JSON.stringify(params));
  const started = now();
  let rpcCount = 0, requestBytes = 0, responseBytes = 0, presentationBytes = 0;
  const pages = [], seenContinuations = new Set();
  const call = async arguments_ => {
    const remaining = policy.callTimeoutMillis - (now() - started);
    if (signal?.aborted) throw new Error('CANCELLED');
    if (remaining <= 0) throw new Error('TIME_LIMIT');
    rpcCount++;
    requestBytes += Buffer.byteLength(JSON.stringify(arguments_), 'utf8');
    let observedBytes = false;
    const reply = await invoke(arguments_, { ...policy, callTimeoutMillis: remaining,
      onResponseBytes: bytes => { observedBytes = true; responseBytes += bytes; } });
    if (!observedBytes) responseBytes += Buffer.byteLength(JSON.stringify(reply), 'utf8');
    return reply;
  };
  // Failure here is ambiguous submission. Let the adapter's bounded transport failure surface unchanged.
  const initial = await call(params);
  const first = initial.document;
  if (!deliveryObject(first)) return initial;
  const rejectedEvidence = first.rejection?.type === 'COMPLETION_UNPROVEN' && first.rejection.detail?.evidence?.type === 'RETAINED'
    ? first.rejection.detail.evidence : null;
  const retained = rejectedEvidence?.result ?? (first.retention?.kind === 'retained' ? first.retention.reference : null);
  const invocation = first.invocation;
  const count = invocation?.accumulated_row_count;
  let liveIdentity = first.live;
  let rowCursor = first.next_cursor ?? (invocation?.preview?.type === 'PREFIX' ? invocation.preview.row_count : count);
  let evidenceCursor = first.evidence_window?.end ?? 0;
  let evidenceTotal = first.evidence_window?.total ?? null;
  let continuation = first.continuation ?? first.qualification?.progress?.checkpoint?.token;
  let next = null;
  const summary = stop => ({ type: 'query_delivery', initial, pages,
    delivery: { stop, rpc_count: rpcCount, request_bytes: requestBytes, response_bytes: responseBytes, result: retained } });
  const bounded = stop => {
    const result = summary(stop);
    // The initial reply alone may exceed model context. Keep an honest blocker and issued reference.
    if (Buffer.byteLength(JSON.stringify(result), 'utf8') > QUERY_DELIVERY_LIMITS.bytes) return {
      type: 'query_delivery', initial: null, pages: [], delivery: {
        stop: 'BYTE_LIMIT', original_outcome: initial.type, rpc_count: rpcCount, request_bytes: requestBytes, response_bytes: responseBytes, result: retained,
      },
    };
    return result;
  };
  const read = (cursor, evidence) => ({ request: {
    type: 'READ_RESULT', result: retained, cursor, evidence_cursor: evidence,
    ...(params.request?.output != null ? { output: params.request.output } : {}),
    ...(rejectedEvidence ? { output: rejectedEvidence.nextQuery?.request?.output } : {}),
    ...(params.request?.executionBudget != null ? { executionBudget: params.request.executionBudget } : {}),
  }, ...(params.verbose != null ? { verbose: params.verbose } : {}) });
  if (signal?.aborted) return bounded('CANCELLED');
  if (now() - started >= policy.callTimeoutMillis) return bounded('TIME_LIMIT');
  if (first.qualification?.progress?.next_action === 'increase_execution_budget') return bounded('BUDGET_INCREASE_REQUIRED');
  if (rejectedEvidence) {
    if (!deliveryReference(retained) || rejectedEvidence.nextQuery?.request?.type !== 'READ_RESULT' ||
        rejectedEvidence.nextQuery.request.result !== retained) return bounded('MALFORMED_PAGE');
    // Rejection preview is a preview only. Retained proof is consumed once from its start.
    rowCursor = 0;
    next = read(0, 0);
  } else if (invocation?.preview?.type === 'PREFIX' || first.next_cursor != null || first.evidence_window?.type === 'MORE') {
    if (!deliveryReference(retained) || !deliveryInteger(rowCursor) || !deliveryInteger(evidenceCursor))
      return bounded('MALFORMED_PAGE');
    next = read(rowCursor, evidenceCursor);
  } else if (deliveryContinuation(continuation)) {
    seenContinuations.add(continuation);
    next = { request: { type: 'RESUME', continuation,
      ...(params.request?.executionBudget != null ? { executionBudget: params.request.executionBudget } : {}) },
      ...(params.verbose != null ? { verbose: params.verbose } : {}) };
  } else return Buffer.byteLength(JSON.stringify(initial), 'utf8') > QUERY_DELIVERY_LIMITS.bytes ? bounded('BYTE_LIMIT') : initial;
  presentationBytes = Buffer.byteLength(JSON.stringify(initial), 'utf8');
  while (next) {
    if (signal?.aborted) return bounded('CANCELLED');
    if (now() - started >= policy.callTimeoutMillis) return bounded('TIME_LIMIT');
    if (rpcCount >= QUERY_DELIVERY_LIMITS.pages) return bounded('PAGE_LIMIT');
    if (presentationBytes + 4096 >= QUERY_DELIVERY_LIMITS.bytes) return bounded('BYTE_LIMIT');
    let reply;
    try { reply = await call(next); }
    catch { return bounded(signal?.aborted ? 'CANCELLED' : now() - started >= policy.callTimeoutMillis ? 'TIME_LIMIT' : 'DELIVERY_UNAVAILABLE'); }
    const page = reply.document;
    if (!deliveryObject(page) || reply.type === 'rejected_document') {
      pages.push(reply);
      return bounded('DELIVERY_UNAVAILABLE');
    }
    if (presentationBytes + Buffer.byteLength(JSON.stringify(reply), 'utf8') + 4096 >= QUERY_DELIVERY_LIMITS.bytes)
      return bounded('BYTE_LIMIT');
    if (next.request.type === 'READ_RESULT') {
      const items = page.items;
      const window = page.evidence_window;
      const expectedQuestion = rejectedEvidence?.question ?? first.question;
      if (!Array.isArray(items) || page.retention?.reference !== retained ||
          JSON.stringify(page.question) !== JSON.stringify(expectedQuestion) ||
          (liveIdentity != null && JSON.stringify(page.live) !== JSON.stringify(liveIdentity)) ||
          (count != null && page.invocation != null && page.invocation.accumulated_row_count !== count)) return bounded('IDENTITY_MISMATCH');
      const rowEnd = rowCursor + items.length;
      const rowNext = page.next_cursor;
      if (rowNext != null && (!deliveryInteger(rowNext) || rowNext !== rowEnd || rowNext < rowCursor) ||
          count != null && (rowEnd > count || rowNext == null && rowEnd !== count)) return bounded('MALFORMED_PAGE');
      if (window != null && (!deliveryInteger(window.start) || !deliveryInteger(window.end) || !deliveryInteger(window.total) ||
          window.start !== evidenceCursor || window.end < window.start || window.end > window.total ||
          evidenceTotal != null && window.total !== evidenceTotal ||
          window.type !== (window.end === window.total ? 'FINAL' : 'MORE') ||
          ['failures', 'omissions', 'walk_observations', 'reference_observations', 'discovery_observations', 'relation_observations']
            .reduce((total, field) => total + (Array.isArray(page[field]) ? page[field].length : 0), 0) !== window.end - window.start)) return bounded('MALFORMED_PAGE');
      if (evidenceTotal != null && window == null) return bounded('MALFORMED_PAGE');
      const evidenceEnd = window?.end ?? evidenceCursor;
      const more = rowNext != null || window?.type === 'MORE';
      if (more && rowEnd === rowCursor && evidenceEnd === evidenceCursor) return bounded('NON_ADVANCING');
      rowCursor = rowEnd;
      evidenceCursor = evidenceEnd;
      evidenceTotal = window?.total ?? evidenceTotal;
      next = more ? read(rowCursor, evidenceCursor) : null;
    } else {
      const token = page.continuation ?? page.qualification?.progress?.checkpoint?.token;
      if (liveIdentity != null && JSON.stringify(page.live) !== JSON.stringify(liveIdentity)) return bounded('IDENTITY_MISMATCH');
      if (token != null && (!deliveryContinuation(token) || seenContinuations.has(token))) return bounded('NON_ADVANCING');
      if (JSON.stringify(page.question) !== JSON.stringify(first.question)) return bounded('IDENTITY_MISMATCH');
      if (token) seenContinuations.add(token);
      next = token ? { ...next, request: { ...next.request, continuation: token } } : null;
    }
    liveIdentity ??= page.live;
    pages.push(reply);
    presentationBytes += Buffer.byteLength(JSON.stringify(reply), 'utf8');
    if (signal?.aborted) return bounded('CANCELLED');
    if (now() - started >= policy.callTimeoutMillis) return bounded('TIME_LIMIT');
  }
  return bounded('DELIVERED');
}

function queryDeliveryFailed(result) {
  return result.type === 'query_delivery'
    ? result.delivery.stop !== 'DELIVERED' || !result.initial || ['rejected', 'rejected_document'].includes(result.initial.type)
    : ['rejected', 'rejected_document'].includes(result.type);
}
