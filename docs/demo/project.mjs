// Project only public fixture text, finite result states, and measured durations.
// Raw RPC documents and their opaque references stay outside the repository.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const [capture, auditFile] = process.argv.slice(2);
assert(capture && auditFile, 'Usage: node docs/demo/project.mjs CAPTURE_ROOT AUDIT_JSON');
const read = path => JSON.parse(readFileSync(path, 'utf8'));
const hash = path => createHash('sha256').update(readFileSync(path)).digest('hex');
const sourceFile = resolve(here, '../../relation/intellij/testData/request-response/src/main/kotlin/fixture/variants/v0/V0.kt');
const source = readFileSync(sourceFile, 'utf8');
assert.equal(hash(sourceFile), '75defdcdc8cb7cca72dd1991f5797ab71d8deb35acd76316813b165b5fec57a6');
const audit = read(auditFile);
assert.equal(audit.sourceRevision, 'f1ad6f4ce7b13be6a574183b2ba505149d92b581');
assert.equal(audit.checks.length, 112);
assert(audit.checks.every(check => check.pass === true));
assert.deepEqual(audit.failures, []);
const release = read(join(capture, 'release.json'));
assert.equal(release.tag_name, 'v0.50.2');
assert.equal(release.target_commitish, audit.sourceRevision);
const input = name => join(capture, 'v0-public', name);
const reply = name => read(join(input(name), 'stdout.jsonl'));
const request = name => read(join(input(name), 'request.jsonl')).request;
const rows = [...reply('feature-handle').document.items, ...reply('handle-callees').document.items,
  ...reply('transport-send').document.items];
const byIdentity = name => {
  const row = rows.find(row => row.signature.qualifiedIdentity === `fixture.variants.v0.${name}`);
  assert(row, `Missing issued declaration: ${name}`);
  return row;
};
const H = byIdentity('Feature.handle');
const B = byIdentity('BackendClient.fetch');
const C = byIdentity('ClientRequest.<init>');
const S = byIdentity('Transport.send');
assert.equal(C.kind, 'constructor');
const line = offset => source.slice(0, offset).split('\n').length;
const span = range => {
  assert(Number.isInteger(range.start) && Number.isInteger(range.end));
  assert(range.start >= 0 && range.start < range.end && range.end <= source.length);
  return { start: range.start, end: range.end, line: line(range.start), text: source.slice(range.start, range.end) };
};
const fieldNames = new Map();
for (const match of source.matchAll(/data class (\w+)\((val (\w+): String)\)/g)) {
  const start = match.index + match[0].indexOf(match[2]);
  fieldNames.set(`${start}:${start + match[2].length}`, `${match[1]}.${match[3]}`);
}
const field = declaration => {
  const name = fieldNames.get(`${declaration.range.start}:${declaration.range.end}`);
  assert(name, 'Field does not belong to the frozen public fixture');
  return name;
};
const site = value => ({
  ...span(value.range), role: value.role.type,
  fields: value.valuePath.map(value => {
    assert.equal(value.type, 'FIELD');
    return field(value.declaration);
  }),
});
const path = document => {
  assert.equal(document.items.length, 1);
  assert.equal(document.impact_accounting.originalPathCount, 1);
  assert.equal(document.impact_accounting.pagePathCount, 1);
  assert(!('next_cursor' in document));
  const value = document.items[0].path;
  return {
    producer: site(value.producer),
    steps: value.steps.map(({ transfer }) => ({
      kind: transfer.kind, source: site(transfer.source), target: site(transfer.target),
      ...(transfer.kind === 'FIELD_READ' ? { field: field(transfer.evidence.operation.field) } : {}),
    })),
    terminal: { type: value.terminal.type,
      ...(value.terminal.type === 'UNRESOLVED_FLOW' ? { cause: value.terminal.cause, site: site(value.terminal.site) } : {}),
    },
    accounting: document.impact_accounting.status,
  };
};
const incoming = reply('incoming');
const outgoing = reply('outgoing');
assert.equal(incoming.type, 'complete');
assert.deepEqual(incoming.document.invocation.preview, { type: 'PREFIX', row_count: 0, encoded_bytes: 2 });
assert.equal(incoming.document.next_cursor, 0);
assert.equal(outgoing.type, 'rejected_document');
assert.equal(outgoing.document.rejection.type, 'COMPLETION_UNPROVEN');
assert.equal(outgoing.document.rejection.detail.policyProgress.type, 'EVIDENCE_ONLY');
assert.equal(reply('outgoing-read-default-0').document.failure, 'RESULT_TOO_LARGE');
assert.deepEqual(request('outgoing-read-expanded-0').executionBudget, { maxResults: 1, maxReturnedBytes: 524288 });
const inRead = reply('incoming-read-default-0');
const outRead = reply('outgoing-read-expanded-0');
assert.equal(inRead.type, 'complete');
assert.equal(outRead.type, 'qualified');
assert.equal(outRead.document.interpretation.type, 'POLICY_REJECTED_EVIDENCE');
for (const [name, enclosing, callable, anchor] of [
  ['incoming', B.ref, S.ref, { start: 659, end: 686 }],
  ['outgoing', H.ref, C.ref, { start: 883, end: 917 }],
]) {
  const q = request(name);
  assert.equal(q.source.type, 'IMPACT');
  assert.equal(q.source.flow, 'KOTLIN_FORWARD_V1');
  assert.deepEqual(q.source.seeds, [{ enclosing, callable, anchor }]);
  assert.deepEqual(q.source.models, []);
  assert.deepEqual(q.source.declarations, []);
  assert(!('executionBudget' in q));
  assert.equal(q.source.domain.directory, 'logging/src/main/kotlin/fixture/variants/v0');
  assert.deepEqual(q.source.domain.sourceSets, ['main']);
}
const names = ['feature-handle', 'handle-callees', 'incoming', 'incoming-read-default-0',
  'outgoing', 'outgoing-read-default-0', 'outgoing-read-expanded-0'];
// Fresh capture templates contain descriptive placeholders, never usable tokens.
const aliases = new Map([[H.ref, '<issued Feature.handle ref>'], [B.ref, '<issued BackendClient.fetch ref>'],
  [C.ref, '<issued ClientRequest constructor ref>'], [S.ref, '<issued Transport.send ref>'],
  [request('incoming-read-default-0').result, '<retained incoming result>'],
  [request('outgoing-read-default-0').result, '<retained outgoing evidence result>']]);
const templateNames = ['feature-handle', 'handle-callees', 'transport-send', ...names.slice(2)];
const templates = templateNames.map(name => {
  const q = request(name);
  const projected = JSON.stringify(q, (key, value) => {
    if (typeof value !== 'string') return value;
    if (aliases.has(value)) return aliases.get(value);
    assert(value.length < 120 && !value.startsWith('/'), `Unrecognized private request value: ${key}`);
    return value;
  });
  assert(!/canonical-signature|\/Users\/|\/private\/|\/tmp\//.test(projected));
  return { name, request: JSON.parse(projected) };
});
writeFileSync(join(here, 'requests.json'), JSON.stringify({
  note: 'Templates from actual calls. Replace placeholders with fresh issued refs/results in a prepared public fixture. Each RUN uses a fresh query-session child; retain its READ_RESULT lineage. These templates do not launch or select an IDE.',
  calls: templates,
}, null, 2) + '\n');
const calls = names.map(name => {
  const p = read(join(input(name), 'process.json'));
  assert.equal(p.status, 'exited');
  assert.equal(p.exit, 0);
  assert(p.first_complete_stdout_seconds > 0 && p.first_complete_stdout_seconds < 90);
  return { name, requestType: request(name).type, responseType: reply(name).type,
    firstCompleteStdoutSeconds: p.first_complete_stdout_seconds,
    completionSeconds: p.completion_seconds,
    hashes: Object.fromEntries(['request.jsonl', 'stdout.jsonl', 'process.json'].map(file => [file, hash(join(input(name), file))])),
  };
});
const value = {
  version: release.tag_name, sourceRevision: audit.sourceRevision,
  source: { path: 'relation/intellij/testData/request-response/src/main/kotlin/fixture/variants/v0/V0.kt',
    sha256: hash(sourceFile), text: source },
  scope: { variant: 'V0', sourceSets: ['main'], directory: 'logging/src/main/kotlin/fixture/variants/v0',
    semantics: 'KOTLIN_FORWARD_V1', completion: 'COMPLETE_ONLY', authority: 'COMPILER_RESOLVED_STATIC_V1', models: [] },
  audit: { checks: audit.checks.length, failures: 0, sha256: hash(auditFile) },
  declarations: [H, ...reply('handle-callees').document.items].map(row => ({
    name: row.signature.qualifiedIdentity, kind: row.kind,
    range: span({ start: row.location.range.startInclusive, end: row.location.range.endExclusive }),
  })),
  calls,
  incoming: { responseType: incoming.type, previewRows: incoming.document.invocation.preview.row_count,
    retainedResponseType: inRead.type, path: path(inRead.document) },
  outgoing: { responseType: outgoing.type, rejection: outgoing.document.rejection.type,
    policyProgress: outgoing.document.rejection.detail.policyProgress.type,
    defaultReadFailure: reply('outgoing-read-default-0').document.failure,
    presentationBudget: request('outgoing-read-expanded-0').executionBudget,
    retainedResponseType: outRead.type, interpretation: outRead.document.interpretation.type,
    path: path(outRead.document) },
};
assert.equal(value.incoming.path.steps.length, 13);
assert.equal(value.outgoing.path.steps.length, 11);
assert.equal(value.incoming.path.terminal.type, 'SUPPORTED_DOMAIN_END');
assert.equal(value.outgoing.path.terminal.cause, 'UNMODELED_CALL');
const serialized = JSON.stringify(value, null, 2) + '\n';
assert(!/\/Users\/|\/private\/|\/tmp\/|canonical-signature|compilerIdentity|"ref"|"host"|"epoch"/.test(serialized));
writeFileSync(join(here, 'evidence.json'), serialized);
console.log(`Projected ${calls.length} actual calls; 13 incoming and 11 outgoing transfers; no raw references exported.`);
