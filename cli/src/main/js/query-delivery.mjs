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
const QUERY_DELIVERY_GUIDANCE = " The client awaits bounded query output in one invocation. Default text contains exact rows, shared descriptions, coverage, original outcome and delivery blockers. Detailed source/evidence is available through the shown authority-bound READ_RESULT request with verbose:true. Distinct occurrences remain distinct; a retained rejection is still a rejection. Handles may expire or become stale; never rerun a query implicitly.";
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

const queryEvidenceFields = Object.freeze([
  'failures', 'omissions', 'walk_observations', 'reference_observations', 'discovery_observations', 'relation_observations',
]);

/** Human presentation only. The admitted canonical result remains unchanged in adapter details. */
function queryPresentation(result, verbose = false) {
  const canonical = () => JSON.stringify(result);
  if (verbose) return canonical();
  const initial = result.type === 'query_delivery' ? result.initial : result;
  const first = initial?.document;
  const rejectionEvidence = first?.rejection?.type === 'COMPLETION_UNPROVEN' &&
    first.rejection.detail?.evidence?.type === 'RETAINED' ? first.rejection.detail.evidence : null;
  if (!first || !Array.isArray(first.items) && !rejectionEvidence) return canonical();
  if (!first.question && !rejectionEvidence?.question) return canonical();
  const replies = result.type === 'query_delivery' ? [initial, ...result.pages] : [initial];
  const retained = result.delivery?.result ?? rejectionEvidence?.result ??
    (first.retention?.kind === 'retained' ? first.retention.reference : null);
  const rows = replies.flatMap(reply => Array.isArray(reply.document?.items) ? reply.document.items : []);
  // A rejection preview is a copy of the retained prefix. Keep any occurrence not actually delivered.
  if (rejectionEvidence) {
    const delivered = new Set(rows.map(row => JSON.stringify(row)));
    rows.unshift(...rejectionEvidence.preview.filter(row => !delivered.has(JSON.stringify(row))));
  }
  const metadata = document => Object.fromEntries(Object.entries(document).filter(([key]) =>
    !['items', ...queryEvidenceFields, 'operation', 'page_progress', 'evidence_window', 'next_cursor',
      'continuation', 'invocation', 'retention'].includes(key)));
  const summary = metadata(first);
  if (first.invocation) summary.invocation = { accumulated_row_count: first.invocation.accumulated_row_count,
    stop: first.invocation.stop, preview: { type: first.invocation.preview.type, row_count: first.invocation.preview.row_count } };
  if (rejectionEvidence) {
    // Issued preview/recovery control is rendered once below; rejection and coverage remain authoritative.
    summary.rejection = JSON.parse(JSON.stringify(first.rejection));
    delete summary.rejection.detail.evidence.preview;
    delete summary.rejection.detail.evidence.nextQuery;
  }
  const descriptions = new Map(), descriptionKeys = new WeakMap();
  const consider = value => {
    if (!deliveryObject(value)) return;
    const key = JSON.stringify(value);
    descriptionKeys.set(value, key);
    const entry = descriptions.get(key);
    if (entry) entry.count++;
    else descriptions.set(key, { count: 1, value });
  };
  for (const row of rows) {
    if (row.type === 'reference-occurrence') {
      consider(row.occurrence?.target);
      consider(row.occurrence?.ownership?.declaration);
    }
  }
  const shared = [];
  for (const entry of descriptions.values()) if (entry.count > 1) {
    entry.label = `description${shared.length + 1}`;
    shared.push(entry);
  }
  const describe = value => {
    const entry = descriptions.get(descriptionKeys.get(value));
    return entry?.label ? { $description: entry.label } : value;
  };
  const rowPresentation = row => {
    if (row.type === 'exact-symbol') return Object.fromEntries(Object.entries(row).filter(([key, value]) =>
      !['name', 'location', 'signature', 'source', 'matches', 'connections'].includes(key) ||
      value != null && (!Array.isArray(value) || value.length > 0)));
    if (row.type !== 'reference-occurrence') return row;
    const occurrence = row.occurrence;
    return { ...row, occurrence: { ...occurrence, target: describe(occurrence.target),
      ownership: occurrence.ownership?.type === 'declaration-owned'
        ? { ...occurrence.ownership, declaration: describe(occurrence.ownership.declaration) } : occurrence.ownership } };
  };
  const stop = result.delivery?.stop ?? 'DELIVERED';
  const lines = [`Kast query: ${initial.type}; delivery: ${stop}`, 'Summary:', JSON.stringify(summary)];
  if (stop !== 'DELIVERED') lines.push('Output is incomplete; retain the semantic outcome and delivery blocker above.');
  lines.push(`Rows (${rows.length}):`, ...rows.map(row => JSON.stringify(rowPresentation(row))));
  if (shared.length) lines.push('Shared descriptions:', ...shared.map(entry => JSON.stringify({ [entry.label]: entry.value })));
  const evidence = Object.fromEntries(queryEvidenceFields.map(field => [field,
    replies.flatMap(reply => Array.isArray(reply.document?.[field]) ? reply.document[field] : [])]));
  const window = replies.map(reply => reply.document?.evidence_window).filter(Boolean).at(-1);
  lines.push('Evidence delivery:', JSON.stringify({ delivered_entries: Object.values(evidence).reduce((sum, entries) => sum + entries.length, 0),
    ...(window ? { window } : {}) }), 'Evidence:');
  for (const [field, entries] of Object.entries(evidence)) if (entries.length) {
    // Finite failures and omissions always stay inline. Detail omission is explicit and recoverable.
    lines.push(JSON.stringify({ [field]: retained && !['failures', 'omissions'].includes(field)
      ? { available_entries: entries.length, detail: 'READ_RESULT' } : entries }));
  }
  const observations = new Set([JSON.stringify(metadata(first))]);
  for (const reply of replies.slice(1)) {
    if (!reply.document) { lines.push('Delivery observation:', JSON.stringify(reply)); continue; }
    const detail = metadata(reply.document), encoded = JSON.stringify(detail);
    if (!observations.has(encoded)) {
      observations.add(encoded);
      lines.push('Delivery observation:', encoded);
    }
  }
  const question = first.question ?? rejectionEvidence?.question;
  // Canonical output tags/fields serialize lower-case; public READ_RESULT uses the generated CAPS_CASE enum.
  const output = question?.output ? { ...question.output, type: question.output.type.toUpperCase(),
    ...(question.output.fields ? { fields: question.output.fields.map(field => field.toUpperCase()) } : {}) } : null;
  if (retained) lines.push('Details:', JSON.stringify({ request: { type: 'READ_RESULT', result: retained,
    cursor: 0, evidence_cursor: 0, ...(output ? { output } : {}) }, verbose: true }),
  'Details use the original live authority and may expire or become stale. An unavailable handle is not rerun.');
  else lines.push('Details: no retained handle; all available evidence is inline.');
  const text = lines.join('\n');
  // Preserve existing presentation bounds. A projection never buys a larger drain or erases a large failure.
  return Buffer.byteLength(text, 'utf8') <= QUERY_DELIVERY_LIMITS.bytes
    ? text : canonical();
}
