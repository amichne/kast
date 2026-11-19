import { createHash } from 'node:crypto';

// Pi owns token normalization and pricing. This boundary preserves delivered facts;
// it never reconstructs a price from counters or the current public rate catalog.
const counters = ['input', 'output', 'cacheRead', 'cacheWrite'];
const hash = value => createHash('sha256').update(value).digest('hex');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const integer = value => Number.isSafeInteger(value) && value >= 0;
const money = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
const equalMoney = (a, b) => Math.abs(a - b) <= Math.max(1e-12, Math.abs(a) * 1e-10);
const unique = values => [...new Set(values)].sort();
const sha256 = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const limits = Object.freeze({ records: 512, recordBytes: 524_288, tools: 128, valueBytes: 65_536, evidenceBytes: 524_288 });

function usageOf(value) {
  if (value === undefined || value === null) return { state: 'missing' };
  if (!object(value) || !counters.every(key => integer(value[key])) ||
      !integer(value.totalTokens) || counters.reduce((sum, key) => sum + value[key], 0) !== value.totalTokens ||
      !['reasoning', 'cacheWrite1h'].every(key => value[key] === undefined || integer(value[key])) ||
      (value.reasoning !== undefined && value.reasoning > value.output) ||
      (value.cacheWrite1h !== undefined && value.cacheWrite1h > value.cacheWrite)) {
    return { state: 'invalid', reason: 'INVALID_NATIVE_USAGE' };
  }
  return { state: 'reported', value: Object.fromEntries([...counters, 'totalTokens', 'reasoning', 'cacheWrite1h']
    .filter(key => value[key] !== undefined).map(key => [key, value[key]])) };
}

function ratesOf(value) {
  if (!object(value) || !counters.every(key => money(value[key]))) return undefined;
  const rates = Object.fromEntries(counters.map(key => [key, value[key]]));
  if (value.tiers !== undefined) {
    if (!Array.isArray(value.tiers) || value.tiers.length > 64) return undefined;
    const tiers = value.tiers.map(tier => {
      if (!object(tier) || !integer(tier.inputTokensAbove) || !counters.every(key => money(tier[key]))) return undefined;
      return Object.fromEntries(['inputTokensAbove', ...counters].map(key => [key, tier[key]]));
    });
    if (tiers.some(tier => !tier)) return undefined;
    rates.tiers = tiers;
  }
  return rates;
}

export function snapshotPiCatalog(copiedBytes, expectedModel) {
  if (copiedBytes === undefined) return { state: 'unavailable', reason: 'NO_COPIED_MODEL_STORE' };
  const bytes = Buffer.from(copiedBytes);
  const snapshot = { state: 'copied', sha256: hash(bytes), bytes: bytes.length,
    selected: { state: 'unavailable', reason: 'MODEL_ENTRY_UNAVAILABLE' } };
  try {
    const split = expectedModel.indexOf('/');
    const provider = expectedModel.slice(0, split), id = expectedModel.slice(split + 1);
    const entry = JSON.parse(bytes.toString('utf8'))[provider];
    const models = entry?.models;
    const matches = Array.isArray(models) ? models.filter(model => model?.id === id && model?.provider === provider) : [];
    if (matches.length !== 1) return snapshot;
    const model = matches[0], cost = ratesOf(model.cost);
    if (!cost) return snapshot;
    snapshot.selected = { state: 'available', provider, model: id, cost,
      ...(typeof model.api === 'string' && { api: model.api }),
      ...(integer(entry.checkedAt) && { checkedAt: entry.checkedAt }),
      ...(integer(entry.lastModified) && { lastModified: entry.lastModified }) };
    return snapshot;
  } catch { return snapshot; }
}

function identityOf(message) {
  const identity = {};
  for (const key of ['api', 'provider', 'model', 'responseModel', 'responseId', 'providerThinkingLevel', 'thinkingLevel']) {
    if (typeof message[key] === 'string' && message[key].length <= 1024) identity[key] = message[key];
  }
  for (const key of ['timestamp', 'durationMs']) if (money(message[key])) identity[key] = message[key];
  return identity;
}

function applicableRates(identity, catalog) {
  const selected = catalog?.selected;
  if (catalog?.state !== 'copied' || !sha256(catalog.sha256) || selected?.state !== 'available' ||
      selected.provider !== identity.provider || selected.model !== identity.model ||
      (identity.responseModel && identity.responseModel !== selected.model) || !ratesOf(selected.cost)) {
    return { state: 'unknown', reason: 'APPLICABLE_COPIED_RATES_UNAVAILABLE' };
  }
  return { state: 'catalog-entry', catalogSha256: catalog.sha256,
    provider: selected.provider, model: selected.model, cost: selected.cost };
}

function costOf(value, identity, catalog) {
  if (value === undefined || value === null) return { state: 'missing' };
  if (!object(value) || ![...counters, 'total'].every(key => money(value[key])) ||
      (value.totalFromProvider !== undefined && typeof value.totalFromProvider !== 'boolean')) {
    return { state: 'invalid', reason: 'INVALID_NATIVE_COST' };
  }
  const provenance = value.totalFromProvider === true ? 'provider-reported' : 'catalog-estimate';
  if (provenance === 'catalog-estimate' && !equalMoney(counters.reduce((sum, key) => sum + value[key], 0), value.total)) {
    return { state: 'invalid', reason: 'INCONSISTENT_ESTIMATED_COST' };
  }
  return { state: 'reported', provenance,
    value: Object.fromEntries([...counters, 'total', 'totalFromProvider'].filter(key => value[key] !== undefined)
      .map(key => [key, value[key]])), rates: applicableRates(identity, catalog) };
}

function recordOf(message, role, id, catalog) {
  const identity = identityOf(message);
  const usage = usageOf(message.usage), cost = costOf(message.usage?.cost, identity, catalog);
  // Pi initializes counters before a request. A failed zero estimate is not a
  // provider acknowledgement that no billable work occurred; retain it as such.
  const unobserved = (['error', 'aborted'].includes(message.stopReason) || message.isError === true) && usage.state === 'reported' &&
    usage.value.totalTokens === 0 && cost.state === 'reported' && cost.provenance === 'catalog-estimate' && cost.value.total === 0;
  return { id, role, identity,
    ...(role === 'participant' && { stopReason: ['pending', 'stop', 'length', 'toolUse', 'error', 'aborted', 'deferred'].includes(message.stopReason)
      ? message.stopReason : 'unknown' }),
    usage: unobserved ? { state: 'unobserved', reason: 'FAILED_ATTEMPT_UNMETERED', initialized: usage.value } : usage,
    cost: unobserved ? { state: 'unobserved', reason: 'FAILED_ATTEMPT_UNMETERED', initialized: cost.value } : cost };
}

function summary(records, reasons) {
  let providerReported = 0, estimated = 0;
  const issues = [...reasons];
  for (const record of records) {
    if (record.cost.state !== 'reported') { issues.push(record.cost.reason ?? 'NATIVE_COST_MISSING'); continue; }
    if (record.cost.provenance === 'provider-reported') providerReported += record.cost.value.total;
    else {
      estimated += record.cost.value.total;
      if (record.cost.rates.state !== 'catalog-entry') issues.push('APPLICABLE_COPIED_RATES_UNAVAILABLE');
    }
  }
  const knownTotal = providerReported + estimated;
  if (![knownTotal, providerReported, estimated].every(money)) issues.push('COST_SUM_OVERFLOW');
  const provenance = records.some(record => record.cost.provenance === 'provider-reported') ?
    (records.some(record => record.cost.provenance === 'catalog-estimate') ? 'mixed' : 'provider-reported') :
    (records.some(record => record.cost.provenance === 'catalog-estimate') ? 'catalog-estimate' : 'none');
  return issues.length ? { state: 'incomplete', knownTotal, providerReported, estimated, provenance, reasons: unique(issues) } :
    { state: 'complete', total: knownTotal, knownTotal, providerReported, estimated, provenance };
}

function costsFor(records, coverage) {
  const participant = records.filter(record => record.role === 'participant');
  const helper = records.filter(record => record.role === 'helper');
  const common = [
    ...(coverage.conflictingRecords ? ['CONFLICTING_RESPONSE_ID'] : []),
    ...(coverage.omittedRecords ? ['ACCOUNTING_RECORDS_OMITTED'] : []),
  ];
  const primaryReasons = [...common, ...(!participant.length ? ['PARTICIPANT_NOT_OBSERVED'] : []),
    ...(coverage.unsettled ? ['UNSETTLED_ATTEMPT'] : [])];
  const helperReasons = [...common, ...(coverage.unknownHelpers ? ['HELPER_USAGE_UNKNOWN'] : [])];
  return { participant: summary(participant, primaryReasons), helper: summary(helper, helperReasons),
    task: summary(records, [...primaryReasons, ...helperReasons]) };
}

function boundedText(text, budget) {
  if (typeof text !== 'string') return { state: 'missing' };
  const bytes = Buffer.from(text);
  let kept = bytes.subarray(0, Math.max(0, Math.min(limits.valueBytes, budget.remaining)));
  // Avoid inventing a replacement character when the byte cap splits UTF-8.
  while (kept.length && Buffer.from(kept.toString('utf8')).length > kept.length) kept = kept.subarray(0, kept.length - 1);
  budget.remaining -= kept.length;
  return { state: kept.length === bytes.length ? 'complete' : 'truncated',
    text: kept.toString('utf8'), bytes: bytes.length, sha256: hash(bytes) };
}

function evidenceOf(events, tools, unsettled) {
  const budget = { remaining: limits.evidenceBytes };
  const terminal = events.findLast(event => event.type === 'message_end' && event.message?.role === 'assistant')?.message;
  let text = Array.isArray(terminal?.content) ? terminal.content.filter(part => part?.type === 'text' && typeof part.text === 'string')
    .map(part => part.text).join('') : undefined;
  let active = false;
  const deltas = new Map();
  for (const event of events) {
    if (event.type === 'message_start' && event.message?.role === 'assistant') { active = true; deltas.clear(); }
    else if (event.type === 'message_end' && event.message?.role === 'assistant') active = false;
    else if (active && event.type === 'message_update' && event.assistantMessageEvent?.type === 'text_delta') {
      const update = event.assistantMessageEvent;
      if (typeof update.delta === 'string') deltas.set(update.contentIndex, (deltas.get(update.contentIndex) ?? '') + update.delta);
    }
  }
  const partial = active ? [...deltas.entries()].sort(([a], [b]) => a - b).map(([, value]) => value).join('') : '';
  if (partial) text = partial;
  const final = { ...boundedText(text, budget), origin: partial ? 'active-assistant-deltas' : 'last-completed-assistant',
    delivery: unsettled || active ? 'interrupted' : terminal?.stopReason === 'stop' ? 'final' : 'nonfinal' };
  const results = [...tools.values()].slice(0, limits.tools).map(tool => ({
    toolCallId: tool.id, toolName: tool.name,
    result: boundedText(tool.content === undefined ? undefined : JSON.stringify(tool.content), budget),
  }));
  const omitted = JSON.stringify([...tools.values()].slice(limits.tools).map(({ id, name, content }) => ({ id, name, content })));
  return { schemaVersion: 1, scope: 'delivered-assistant-text-and-tool-content', limits, final, tools: results,
    omittedTools: Math.max(0, tools.size - results.length),
    ...(tools.size > results.length && { omittedToolsBytes: Buffer.byteLength(omitted), omittedToolsSha256: hash(omitted) }),
    retainedBytes: limits.evidenceBytes - budget.remaining };
}

export function observePiTrial(events, { runtime, toolUsagePolicy = 'unknown', unsettled = false } = {}) {
  if (!['unknown', 'no-model-helpers'].includes(toolUsagePolicy)) throw Error('Unknown Pi tool usage policy');
  const records = new Map(), tools = new Map(), messages = new Map();
  let duplicateRecords = 0, conflictingRecords = 0, ordinal = 0;
  const remember = record => {
    const previous = records.get(record.id);
    if (!previous) { records.set(record.id, record); return; }
    duplicateRecords++;
    if (JSON.stringify(previous) !== JSON.stringify(record)) conflictingRecords++;
  };
  for (const event of events) {
    const message = event.message;
    if (event.type === 'message_end' && message?.role === 'assistant') {
      const id = typeof message.responseId === 'string' && message.responseId ?
        `participant:${message.provider}:${message.responseId}` : `participant:event:${ordinal++}`;
      remember(recordOf(message, 'participant', id, runtime?.catalog));
      if (!messages.has(id)) messages.set(id, message);
    }
    const result = event.type === 'tool_execution_end' ? event.result :
      event.type === 'message_end' && message?.role === 'toolResult' ? message : undefined;
    if (event.type === 'tool_execution_start') {
      tools.set(event.toolCallId, { id: event.toolCallId, name: event.toolName, pending: true, metered: false });
    }
    if (result) {
      const id = event.toolCallId ?? result.toolCallId;
      const previous = tools.get(id);
      tools.set(id, { id, name: event.toolName ?? result.toolName,
        content: result.content ?? previous?.content, pending: false,
        metered: result.usage !== undefined || previous?.metered === true,
        nestedCalls: result.nestedCalls ?? previous?.nestedCalls });
      if (result.usage !== undefined) remember(recordOf(result, 'helper', `helper:${id}`, runtime?.catalog));
    }
  }
  const allRecords = [...records.values()];
  const retained = [], omitted = [];
  let recordBytes = 2;
  for (const record of allRecords) {
    const bytes = Buffer.byteLength(JSON.stringify(record)) + (retained.length ? 1 : 0);
    if (retained.length < limits.records && recordBytes + bytes <= limits.recordBytes) {
      retained.push(record); recordBytes += bytes;
    } else omitted.push(record);
  }
  const omittedText = JSON.stringify(omitted);
  const nestedUnknown = tool => tool.nestedCalls !== undefined &&
    (tool.nestedCalls?.complete !== true || !Array.isArray(tool.nestedCalls?.calls) || tool.nestedCalls.calls.length > 0);
  const coverage = { toolUsagePolicy, unsettled: unsettled === true,
    unknownHelpers: [...tools.values()].filter(tool => tool.pending ||
      (toolUsagePolicy !== 'no-model-helpers' && (!tool.metered || nestedUnknown(tool)))).length,
    conflictingRecords, omittedRecords: allRecords.length - retained.length };
  const primary = allRecords.filter(record => record.role === 'participant');
  const usage = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 };
  const inputs = [];
  let invalidUsage = 0;
  for (const record of primary) {
    if (record.usage.state !== 'reported') { invalidUsage++; continue; }
    for (const key of counters) usage[key] += record.usage.value[key];
    usage.total += record.usage.value.totalTokens;
    inputs.push(record.usage.value.input + record.usage.value.cacheRead + record.usage.value.cacheWrite);
  }
  const completeUsage = primary.length > 0 && invalidUsage === 0 && !unsettled && conflictingRecords === 0 &&
    Object.values(usage).every(integer);
  const input = completeUsage ? { state: 'complete', sum: inputs.reduce((a, b) => a + b, 0),
    peak: Math.max(...inputs), final: inputs.at(-1) } :
    { state: 'incomplete', knownSum: inputs.reduce((a, b) => a + b, 0),
      ...(inputs.length && { observedPeak: Math.max(...inputs), lastObserved: inputs.at(-1) }),
      unknownResponses: invalidUsage + (unsettled ? 1 : 0) };
  return { accounting: { schemaVersion: 1, scope: 'pi-delivered-events', records: retained, coverage,
    duplicateRecords, retention: { recordBytes, recordByteLimit: limits.recordBytes,
      ...(omitted.length && { omittedBytes: Buffer.byteLength(omittedText), omittedSha256: hash(omittedText) }) },
    costs: costsFor(retained, coverage), ...(runtime && { runtime }) },
  context: { schemaVersion: 1, responses: primary.length, input,
    usage: completeUsage ? { state: 'complete', value: usage } : { state: 'incomplete', known: usage } },
  evidence: evidenceOf(events, tools, unsettled), usage, completeUsage, invalidUsage,
  providerResponses: primary.length, assistantMessages: [...messages.values()] };
}

// Exported reports are another trust boundary. Admit only the owner's schema,
// recheck record facts and summaries, and never trust an exported scalar total.
function parseAccounting(accounting) {
  const unavailable = reason => ({ state: 'unavailable', reasons: [reason] });
  if (!object(accounting) || accounting.schemaVersion !== 1 || accounting.scope !== 'pi-delivered-events' ||
      !Array.isArray(accounting.records) || accounting.records.length > limits.records ||
      Buffer.byteLength(JSON.stringify(accounting.records)) > limits.recordBytes || !object(accounting.coverage) ||
      !integer(accounting.duplicateRecords)) return unavailable('INVALID_ACCOUNTING_RECEIPT');
  const c = accounting.coverage;
  if (!['unknown', 'no-model-helpers'].includes(c.toolUsagePolicy) || typeof c.unsettled !== 'boolean' ||
      !['unknownHelpers', 'conflictingRecords', 'omittedRecords'].every(key => integer(c[key]))) {
    return unavailable('INVALID_ACCOUNTING_COVERAGE');
  }
  const ids = new Set(), basis = new Set();
  for (const record of accounting.records) {
    if (!object(record) || !['participant', 'helper'].includes(record.role) || typeof record.id !== 'string' || !record.id ||
        ids.has(record.id) || !object(record.identity) || !object(record.cost) || !object(record.usage)) {
      return unavailable('INVALID_ACCOUNTING_RECORD');
    }
    if (record.role === 'participant' && !['provider', 'model'].every(key =>
      typeof record.identity[key] === 'string' && record.identity[key].length > 0)) return unavailable('INVALID_RESPONSE_IDENTITY');
    ids.add(record.id);
    if (record.usage.state === 'reported') {
      if (usageOf(record.usage.value).state !== 'reported') return unavailable('INVALID_NATIVE_USAGE');
    } else if (record.usage.state === 'unobserved') {
      if (record.usage.reason !== 'FAILED_ATTEMPT_UNMETERED' || usageOf(record.usage.initialized).state !== 'reported' ||
          record.usage.initialized.totalTokens !== 0) return unavailable('INVALID_NATIVE_USAGE');
    } else if (!['missing', 'invalid'].includes(record.usage.state)) return unavailable('INVALID_NATIVE_USAGE');
    if (record.cost.state === 'reported') {
      const parsed = costOf(record.cost.value, record.identity);
      if (parsed.state !== 'reported' || parsed.provenance !== record.cost.provenance) return unavailable('INVALID_NATIVE_COST');
      if (record.cost.rates?.state === 'catalog-entry') {
        const rates = record.cost.rates;
        const applicable = applicableRates(record.identity, accounting.runtime?.catalog);
        if (!sha256(rates.catalogSha256) || !ratesOf(rates.cost) ||
            rates.provider !== record.identity.provider || rates.model !== record.identity.model ||
            (record.identity.responseModel && rates.model !== record.identity.responseModel) ||
            JSON.stringify(applicable) !== JSON.stringify(rates)) {
          return unavailable('INVALID_CATALOG_LINEAGE');
        }
        if (record.cost.provenance === 'catalog-estimate') basis.add(rates.catalogSha256);
      } else if (record.cost.rates?.state !== 'unknown') {
        return unavailable('INVALID_RATE_EVIDENCE');
      }
    } else if (record.cost.state === 'unobserved') {
      const initialized = costOf(record.cost.initialized, record.identity);
      if (record.cost.reason !== 'FAILED_ATTEMPT_UNMETERED' || initialized.state !== 'reported' ||
          initialized.value.total !== 0 || initialized.provenance !== 'catalog-estimate') return unavailable('INVALID_NATIVE_COST');
    } else if (!['missing', 'invalid'].includes(record.cost.state)) return unavailable('INVALID_NATIVE_COST');
  }
  const costs = costsFor(accounting.records, c);
  for (const key of ['participant', 'helper', 'task']) {
    const actual = accounting.costs?.[key], expected = costs[key];
    if (actual?.state !== expected.state ||
        (expected.state === 'complete' && actual.provenance !== expected.provenance) ||
        (expected.state === 'incomplete' && (Object.hasOwn(actual, 'total') ||
          JSON.stringify(actual.reasons) !== JSON.stringify(expected.reasons))) ||
        ![...(expected.state === 'complete' ? ['total'] : []), 'knownTotal', 'providerReported', 'estimated'].every(field =>
          money(actual[field]) && equalMoney(actual[field], expected[field]))) return unavailable('INCONSISTENT_COST_SUMMARY');
  }
  return { state: 'ready', records: accounting.records, coverage: c, costs, pricingBasis: [...basis].sort() };
}

export function taskCostForComparison(accounting) {
  const parsed = parseAccounting(accounting);
  if (parsed.state !== 'ready') return parsed;
  const { costs, pricingBasis } = parsed;
  if (costs.task.state !== 'complete') return { state: 'unavailable', reasons: costs.task.reasons };
  return { state: 'ready', total: costs.task.total, provenance: costs.task.provenance, pricingBasis };
}

function contextFacts(parsed) {
  const { coverage, records } = parsed;
  const participants = records.filter(record => record.role === 'participant');
  if (!participants.length || coverage.unsettled || coverage.conflictingRecords || coverage.omittedRecords ||
      participants.some(record => record.usage.state !== 'reported')) return { state: 'unavailable', reasons: ['INCOMPLETE_CONTEXT'] };
  const inputs = participants.map(record => record.usage.value.input + record.usage.value.cacheRead + record.usage.value.cacheWrite);
  const inputSum = inputs.reduce((sum, value) => sum + value, 0);
  if (!integer(inputSum)) return { state: 'unavailable', reasons: ['CONTEXT_SUM_OVERFLOW'] };
  return { state: 'ready', inputSum, peakInput: Math.max(...inputs), finalInput: inputs.at(-1), responses: participants.length };
}

export function contextForComparison(accounting) {
  const parsed = parseAccounting(accounting);
  return parsed.state === 'ready' ? contextFacts(parsed) : parsed;
}

export function piMeasurementsForReport(accounting) {
  const parsed = parseAccounting(accounting);
  if (parsed.state !== 'ready') return parsed;
  const complete = contextFacts(parsed);
  const reported = parsed.records.filter(record => record.role === 'participant' && record.usage.state === 'reported');
  const inputs = reported.map(record => record.usage.value.input + record.usage.value.cacheRead + record.usage.value.cacheWrite);
  return { state: 'admitted', costs: parsed.costs,
    context: complete.state === 'ready' ? { ...complete, state: 'complete' } : {
      state: 'incomplete', knownInputSum: inputs.reduce((sum, value) => sum + value, 0),
      ...(inputs.length && { observedPeak: Math.max(...inputs) }),
      observedResponses: parsed.records.filter(record => record.role === 'participant').length, reasons: complete.reasons,
    } };
}

export function projectPiTrial(trial) {
  const observed = trial?.accounting && trial?.context && trial?.evidence ? trial : observePiTrial([]);
  const { accounting, context, evidence } = observed;
  const cost = taskCostForComparison(accounting);
  const usage = context.usage?.state === 'complete' ? context.usage.value : undefined;
  const finalizedUsage = trial?.finalizedUsage ?? (context.usage?.state === 'incomplete' &&
    context.usage.known?.total > 0 ? context.usage.known : undefined);
  return {
    ...(usage && { tokenUsage: { total: usage.total, prompt: usage.input + usage.cacheRead + usage.cacheWrite,
      completion: usage.output, cached: usage.cacheRead, numRequests: context.responses } }),
    ...(cost.state === 'ready' && { cost: cost.total }),
    metadata: { accounting, context, evidence, ...(finalizedUsage && { finalizedUsage }),
      participantSurface: trial?.participantSurface ?? null },
  };
}
