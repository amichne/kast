// Generated contract version; owned by packaging/generate-public-query.py.
const PUBLIC_TOOL_CONTRACT_VERSION = 14;
// Generated from ToolRpcFailure; owned by packaging/generate-public-query.py.
const TOOL_RPC_FAILURES = [
  "INSTALLATION_STOPPED",
  "OBSERVATION_UNAVAILABLE",
  "CATALOG_UNAVAILABLE",
  "INVALID_COMMAND",
  "REQUEST_TOO_LARGE",
  "UNKNOWN_TOOL",
  "INVALID_ARGUMENTS",
  "OUT_OF_SCOPE",
  "INVOCATION_FAILED",
  "INVALID_RESULT",
  "QUERY_SESSION_TRANSITION_REJECTED",
];
// End generated ToolRpcFailure.
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { joinSession } from "@github/copilot-sdk/extension";

// Generated query delivery; owned by cli/src/main/js/query-delivery.mjs.
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
  // A later authority rejection cannot make already-received evidence depend on a dead handle.
  const detailAvailable = retained && stop !== 'DELIVERY_UNAVAILABLE';
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
    lines.push(JSON.stringify({ [field]: detailAvailable && !['failures', 'omissions'].includes(field)
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
  if (detailAvailable) lines.push('Details:', JSON.stringify({ request: { type: 'READ_RESULT', result: retained,
    cursor: 0, evidence_cursor: 0, ...(output ? { output } : {}) }, verbose: true }),
  'Details use the original live authority and may expire or become stale. An unavailable handle is not rerun.');
  else if (retained) lines.push(`Details: detail delivery unavailable for result ${JSON.stringify(retained)}; all received evidence is inline.`,
    'Delivery does not retry RUN. Follow any canonical recovery action above.');
  else lines.push('Details: no retained handle; all available evidence is inline.');
  const text = lines.join('\n');
  // Preserve existing presentation bounds. A projection never buys a larger drain or erases a large failure.
  return Buffer.byteLength(text, 'utf8') <= QUERY_DELIVERY_LIMITS.bytes
    ? text : canonical();
}
// End generated query delivery.

// Generated query session; owned by cli/src/main/js/query-session.mjs.
/** Invocation-owned sequential query transport. No child survives a logical query. */
function createQuerySession(command, cwd, policy, signal, spawnProcess, environment) {
  let child, pending, timer, closed = false, stopped = null, closing = false;
  let record = Buffer.alloc(0), responseBytes = 0;
  const childEnvironment = { ...environment };
  delete childEnvironment.KAST_TOOL_RPC_COMMAND;
  delete childEnvironment.KAST_EVALUATION_DEADLINE_MS;
  let finishExit;
  const exit = new Promise(resolve => { finishExit = resolve; });
  const failure = reason => new Error(`Kast query session outcome=${reason}; submission may be ambiguous, do not resubmit RUN`);
  const stop = reason => {
    stopped ??= reason;
    if (pending) { const current = pending; pending = null;
      current.policy.onResponseBytes?.(responseBytes); responseBytes = 0; current.reject(failure(stopped)); }
    child?.kill('SIGKILL');
    // Release owned pipes even if another process inherited a descriptor; close must not await that peer.
    child?.stdin.destroy?.(); child?.stdout.destroy?.(); child?.stderr.destroy?.();
  };
  const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); };
  const abort = () => stop('CANCELLED');
  const launch = () => {
    if (signal?.aborted) throw failure('CANCELLED');
    child = spawnProcess(command, ['query-session'], { cwd, env: childEnvironment, stdio: ['pipe', 'pipe', 'pipe'] });
    timer = setTimeout(() => stop('TIMEOUT'), policy.callTimeoutMillis);
    signal?.addEventListener('abort', abort, { once: true });
    child.stderr.on('data', () => {}); // No arbitrary process diagnostics or source payload retained.
    child.stdin.on('error', () => stop('INPUT_FAILED'));
    child.on('error', () => { stop('LAUNCH_FAILED'); cleanup(); finishExit(); });
    child.on('close', code => {
      closed = true;
      if (pending) stop(stopped ?? (code === 0 ? 'MISSING_REPLY' : 'NONZERO_EXIT'));
      cleanup(); finishExit();
    });
    child.stdout.on('data', chunk => {
      if (stopped || closing) return;
      if (!pending) return stop('UNEXPECTED_REPLY');
      responseBytes += chunk.length;
      if (responseBytes > pending.policy.maxResponseBytes) return stop('OUTPUT_OVERFLOW');
      record = Buffer.concat([record, chunk]);
      const boundary = record.indexOf(10);
      if (boundary === -1) return;
      // Exactly one reply per admitted request; never buffer unsolicited records.
      if (boundary !== record.length - 1) return stop('UNEXPECTED_REPLY');
      let reply;
      try { reply = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(record.subarray(0, boundary))); }
      catch { return stop('INVALID_REPLY'); }
      if (!isToolResult(reply)) return stop('INVALID_RESULT');
      const current = pending;
      pending = null; record = Buffer.alloc(0);
      current.policy.onResponseBytes?.(responseBytes); responseBytes = 0;
      current.resolve(reply);
    });
  };
  return {
    invoke(arguments_, deliveryPolicy) {
      if (closed || stopped || closing) return Promise.reject(failure(stopped ?? 'CLOSED'));
      if (pending) return Promise.reject(failure('CONCURRENT_REQUEST'));
      try { if (!child) launch(); } catch (error) { return Promise.reject(error); }
      return new Promise((resolve, reject) => {
        pending = { resolve, reject, policy: deliveryPolicy };
        child.stdin.write(JSON.stringify(arguments_) + '\n');
      });
    },
    async close() {
      closing = true;
      if (!child) return;
      if (!closed) {
        if (pending || stopped || signal?.aborted) stop(stopped ?? 'CANCELLED');
        else child.stdin.end();
        // Child cleanup has a finite ownership bound inside the original session deadline.
        const reap = setTimeout(() => stop('CLOSE_TIMEOUT'), Math.min(1000, policy.callTimeoutMillis));
        try { await exit; } finally { clearTimeout(reap); cleanup(); }
      }
    },
  };
}
// End generated query session.

const installRoot = join(homedir(), ".local", "share", "kast");
const command = process.env.KAST_TOOL_RPC_COMMAND ||
  join(installRoot, "installation", "bin", "kast-tool-rpc-complete");
if (!existsSync(command)) throw new Error("Kast tool RPC command is not installed");

function run(args, input = "", policy = null, signal = null, cwd = process.cwd()) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new Error("Kast invocation CANCELLED before launch"));
    const childEnvironment = { ...process.env };
    delete childEnvironment.KAST_TOOL_RPC_COMMAND;
    delete childEnvironment.KAST_EVALUATION_DEADLINE_MS;
    const child = spawn(command, args, { cwd, env: childEnvironment, stdio: ["pipe", "pipe", "pipe"] });
    const chunks = [];
    let size = 0;
    let stderrBytes = 0;
    let phaseEvidence = "";
    let stderrLine = "";
    let termination = null;
    const started = Date.now();
    const maxResponseBytes = policy?.maxResponseBytes ?? 4_194_304;
    const deadline = policy?.callTimeoutMillis ?? 180_000;
    const stop = (reason) => { termination ??= reason; child.kill("SIGKILL"); };
    const timer = setTimeout(() => stop("TIMEOUT"), deadline);
    const abort = () => stop("CANCELLED");
    const cleanup = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
    };
    const failure = (reason, exitCode = null) => new Error(
      `Kast subprocess stage=${args[0]} outcome=${reason} exitCode=${exitCode ?? "unknown"} elapsedMs=${Date.now() - started} deadlineMs=${deadline} ` +
      `executable=${command} stderrBytes=${stderrBytes} ${phaseEvidence}; mutation state is unknown; do not replay a write; query submission may be ambiguous, do not resubmit it`,
    );
    signal?.addEventListener("abort", abort, { once: true });
    child.stdout.on("data", (chunk) => {
      size += chunk.length;
      if (size > maxResponseBytes) stop("OUTPUT_OVERFLOW");
      else chunks.push(chunk);
    });
    child.stderr.on("data", (chunk) => {
      stderrBytes += chunk.length;
      // Keep only closed diagnostic fields, never source text, arbitrary stderr or reference tokens.
      stderrLine = (stderrLine + chunk.toString("utf8")).slice(-4096);
      const lines = stderrLine.split("\n");
      stderrLine = lines.pop() ?? "";
      for (const line of lines) {
        const phase = line.match(/kast_change stage=([A-Z_]{1,48}) outcome=([A-Z_]{1,32})(?: plan=(plan:[0-9a-f]{64}))?/);
        if (phase) phaseEvidence = `phase=${phase[1]} phaseOutcome=${phase[2]}${phase[3] ? ` plan=${phase[3]}` : ""}`;
      }
    });
    child.on("error", () => { cleanup(); reject(failure("LAUNCH_FAILED")); });
    child.stdin.on("error", () => stop("INPUT_FAILED"));
    child.on("close", (code, exitSignal) => {
      cleanup();
      policy?.onResponseBytes?.(size);
      if (termination) return reject(failure(termination));
      if (code !== 0) return reject(failure(exitSignal ? "SIGNALLED" : "NONZERO_EXIT", code));
      let decoded;
      try { decoded = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)); }
      catch { return reject(failure("INVALID_UTF8")); }
      let result;
      try { result = JSON.parse(decoded); }
      catch { return reject(failure("INVALID_JSON")); }
      if (args[0] === "call" && !isToolResult(result)) return reject(failure("INVALID_RESULT"));
      resolve(result);
    });
    child.stdin.end(input);
  });
}

function isToolResult(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== 2) return false;
  if (value.type === "rejected") return TOOL_RPC_FAILURES.includes(value.failure);
  // Canonical document is opaque here; the Kotlin RPC validates its owning schema.
  return ["complete", "qualified", "rejected_document"].includes(value.type) &&
    value.document !== null && typeof value.document === "object" && !Array.isArray(value.document);
}

const reply = await run(["catalog"]);
if (reply?.type !== "catalog" || reply.catalog?.schemaVersion !== PUBLIC_TOOL_CONTRACT_VERSION ||
    !Number.isSafeInteger(reply.catalog.callTimeoutMillis) || reply.catalog.callTimeoutMillis < 1 ||
    !Number.isSafeInteger(reply.catalog.maxResponseBytes) || reply.catalog.maxResponseBytes < 1 ||
    (reply.catalog.querySession !== undefined && typeof reply.catalog.querySession !== "boolean") ||
    !Array.isArray(reply.catalog.tools)) throw new Error(`Kast catalog admission failed: expected=${PUBLIC_TOOL_CONTRACT_VERSION} observed=${Number.isSafeInteger(reply?.catalog?.schemaVersion) ? reply.catalog.schemaVersion : "invalid"} executable=${command}`);
const policy = reply.catalog;
const names = new Set();
const tools = reply.catalog.tools.map((tool) => {
  if (tool == null || typeof tool.name !== "string" || !/^[a-z][a-z0-9_]{0,63}$/.test(tool.name) || names.has(tool.name) ||
      typeof tool.description !== "string" || !tool.description.trim() ||
      tool.inputSchema == null || typeof tool.inputSchema !== "object" || Array.isArray(tool.inputSchema) ||
      tool.inputSchema.type !== "object" ||
      !["READ", "WRITE"].includes(tool.effect)) throw new Error(`Kast catalog entry admission failed: executable=${command}`);
  names.add(tool.name);
  return {
    name: tool.name,
    description: tool.description + (tool.name === "query_symbols" ? QUERY_DELIVERY_GUIDANCE : ""),
    parameters: tool.inputSchema,
    handler: async (args, context) => {
      const workspace = process.cwd();
      const invoke = (arguments_, deliveryPolicy = policy) =>
        run(["call", tool.name], JSON.stringify(arguments_), deliveryPolicy, context?.signal, workspace);
      const session = tool.name === "query_symbols" && policy.querySession === true
        ? createQuerySession(command, workspace, policy, context?.signal, spawn, process.env) : null;
      let result;
      try {
        result = tool.name === "query_symbols"
          ? await awaitQueryDelivery(args ?? {}, session?.invoke ?? invoke, policy, context?.signal)
          : await invoke(args ?? {});
      } finally { await session?.close(); }
      return {
        textResultForLlm: tool.name === "query_symbols" ? queryPresentation(result, args?.verbose === true) : JSON.stringify(result),
        resultType: queryDeliveryFailed(result) ? "failure" : "success",
      };
    },
  };
});

await joinSession({ tools });
