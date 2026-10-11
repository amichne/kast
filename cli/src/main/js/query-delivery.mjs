// Generated outer delivery contract; owned by cli/src/main/resources/query-delivery.schema.json.
const QUERY_DELIVERY_SCHEMA = {"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://kast.dev/contracts/query-delivery.schema.json","title":"Outer query output delivery","description":"Client output delivery only; DELIVERED does not establish canonical semantic completion. Physical RPC accounting may exceed the number of received replies.","x-page-count-within-rpc-count":true,"$defs":{"CanonicalReply":{"type":"object","required":["type"],"properties":{"type":{"enum":["complete","qualified","rejected_document","rejected"]}},"additionalProperties":true,"description":"Opaque canonical RPC reply: the owning RPC decoder admits all inner semantics separately."}},"oneOf":[{"type":"object","additionalProperties":false,"required":["type","initial","pages","delivery"],"properties":{"type":{"const":"query_delivery"},"initial":{"$ref":"#/$defs/CanonicalReply"},"pages":{"type":"array","maxItems":63,"items":{"$ref":"#/$defs/CanonicalReply"}},"delivery":{"type":"object","additionalProperties":false,"required":["stop","rpc_count","request_bytes","response_bytes","result"],"properties":{"stop":{"enum":["DELIVERED","CANCELLED","TIME_LIMIT","PAGE_LIMIT","BYTE_LIMIT","DELIVERY_UNAVAILABLE","BUDGET_INCREASE_REQUIRED","MALFORMED_PAGE","IDENTITY_MISMATCH","NON_ADVANCING"]},"rpc_count":{"type":"integer","minimum":1,"maximum":64},"request_bytes":{"type":"integer","minimum":0,"maximum":9007199254740991},"response_bytes":{"type":"integer","minimum":0,"maximum":9007199254740991},"result":{"type":["string","null"]}}}}},{"type":"object","additionalProperties":false,"required":["type","initial","pages","delivery"],"properties":{"type":{"const":"query_delivery"},"initial":{"type":"null"},"pages":{"type":"array","maxItems":0,"items":{"$ref":"#/$defs/CanonicalReply"}},"delivery":{"type":"object","additionalProperties":false,"required":["stop","rpc_count","request_bytes","response_bytes","result","original_outcome"],"properties":{"stop":{"const":"BYTE_LIMIT"},"rpc_count":{"type":"integer","minimum":1,"maximum":64},"request_bytes":{"type":"integer","minimum":0,"maximum":9007199254740991},"response_bytes":{"type":"integer","minimum":0,"maximum":9007199254740991},"result":{"type":["string","null"]},"original_outcome":{"enum":["complete","qualified","rejected_document","rejected"]}}}}}]};

const queryDeliveryInvalid = reason => ({ type: 'invalid', reason });
function queryDeliveryMatches(value, node) {
  if (node.$ref) return queryDeliveryMatches(value, QUERY_DELIVERY_SCHEMA.$defs[node.$ref.slice('#/$defs/'.length)]);
  if (node.oneOf) return node.oneOf.filter(branch => queryDeliveryMatches(value, branch)).length === 1;
  if ('const' in node && value !== node.const || node.enum && !node.enum.includes(value)) return false;
  const types = Array.isArray(node.type) ? node.type : node.type ? [node.type] : [];
  const matchesType = type => type === 'null' ? value === null
    : type === 'object' ? value !== null && typeof value === 'object' && !Array.isArray(value)
    : type === 'array' ? Array.isArray(value)
    : type === 'integer' ? Number.isSafeInteger(value) : typeof value === type;
  if (types.length && !types.some(matchesType)) return false;
  if (node.minimum != null && value < node.minimum || node.maximum != null && value > node.maximum) return false;
  if (node.type === 'object') {
    if (node.required.some(key => !Object.hasOwn(value, key))) return false;
    if (node.additionalProperties === false && Object.keys(value).some(key => !Object.hasOwn(node.properties, key))) return false;
    for (const [key, child] of Object.entries(node.properties)) {
      if (Object.hasOwn(value, key) && !queryDeliveryMatches(value[key], child)) return false;
    }
  }
  if (node.type === 'array' && (value.length > node.maxItems || Array.from(value).some(item => !queryDeliveryMatches(item, node.items)))) return false;
  return true;
}

/** Outer admission preserves opaque canonical replies; their authority stays with the RPC owner. */
function admitQueryDeliveryEnvelope(value) {
  if (!queryDeliveryMatches(value, QUERY_DELIVERY_SCHEMA)) return queryDeliveryInvalid('INVALID_OUTER_SHAPE');
  if (value.pages.length + 1 > value.delivery.rpc_count) return queryDeliveryInvalid('INVALID_RPC_ACCOUNTING');
  return value;
}

/** Serialization owner for already admitted canonical replies; no inner document is projected or rewritten. */
function createQueryDeliveryEnvelope(initial, pages, delivery) {
  return admitQueryDeliveryEnvelope({ type: 'query_delivery', initial, pages, delivery });
}

function encodeQueryDeliveryEnvelope(value) {
  const admitted = admitQueryDeliveryEnvelope(value);
  return admitted.type === 'invalid' ? admitted : { type: 'encoded', json: JSON.stringify(admitted) };
}
// End generated outer delivery contract.
/** Portable output delivery. Canonical replies stay opaque, schema-validated RPC documents. */
const QUERY_DELIVERY_GUIDANCE = " The client owns bounded query output delivery. For query_delivery, combine initial items with ordered pages; a rejection preview is not an extra page. DELIVERED accounts only for output: initial still owns semantic completion, qualifications and rejection. Report terminal delivery blockers honestly. No continuation-management calls are needed for a delivered reply.";
const QUERY_DELIVERY_LIMITS = Object.freeze({ pages: 64, bytes: 262_144 });
const deliveryObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const deliveryInteger = value => Number.isSafeInteger(value) && value >= 0 && value <= 1_000_000;
const deliveryReference = value => typeof value === 'string' && /^result:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);
const deliveryBlocker = document => document.qualification?.progress?.next_action === 'increase_execution_budget'
  ? 'BUDGET_INCREASE_REQUIRED' : document.qualification?.progress?.type === 'retention_unavailable' ? 'DELIVERY_UNAVAILABLE' : null;
const deliveryContinuation = value => typeof value === 'string' && /^query-output:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);

// Canonical replies are already admitted by invoke. A rejected internal assembly is a producer defect.
const producedQueryDelivery = (initial, pages, delivery) => {
  const value = createQueryDeliveryEnvelope(initial, pages, delivery);
  if (value.type === 'invalid') throw new Error(value.reason);
  return value;
};

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
  const initialReadEnd = params.request?.type === 'READ_RESULT' && deliveryInteger(params.request.cursor ?? 0) && Array.isArray(first.items)
    ? (params.request.cursor ?? 0) + first.items.length : undefined;
  let rowCursor = first.next_cursor ?? (invocation?.preview?.type === 'PREFIX' ? invocation.preview.row_count : count ?? initialReadEnd);
  let evidenceCursor = first.evidence_window?.end ?? 0;
  let evidenceTotal = first.evidence_window?.total ?? null;
  let continuation = first.continuation ?? first.qualification?.progress?.checkpoint?.token;
  let next = null;
  const summary = stop => producedQueryDelivery(initial, pages,
    { stop, rpc_count: rpcCount, request_bytes: requestBytes, response_bytes: responseBytes, result: retained });
  const bounded = stop => {
    const result = summary(stop);
    // The initial reply alone may exceed model context. Keep an honest blocker and issued reference.
    if (Buffer.byteLength(encodeQueryDeliveryEnvelope(result).json, 'utf8') > QUERY_DELIVERY_LIMITS.bytes) return producedQueryDelivery(
      null, [], {
        stop: 'BYTE_LIMIT', original_outcome: initial.type, rpc_count: rpcCount, request_bytes: requestBytes, response_bytes: responseBytes, result: retained,
      }
    );
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
  if (deliveryBlocker(first)) return bounded(deliveryBlocker(first));
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
    if (deliveryBlocker(page)) {
      pages.push(reply);
      return bounded(deliveryBlocker(page));
    }
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
