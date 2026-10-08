/** Actual Pi session probe; source intent and native/build pins are separate authorities. */
import { readFile, writeFile, mkdir, realpath } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execute = promisify(execFile);

let stage = 'INPUT_ADMISSION';
async function main() {
const options = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  if (!key?.startsWith('--') || !process.argv[index + 1] || options.has(key)) throw new Error('INVALID_ARGUMENTS');
  options.set(key, process.argv[index + 1]);
}
const required = ['--root', '--owned-root', '--candidate', '--oracle', '--start-pin', '--pi-package', '--auth', '--model', '--qualification-slice'];
if (required.some(key => !options.has(key)) || [...options.keys()].some(key => !required.includes(key))) throw new Error('INVALID_ARGUMENTS');
const root = resolve(options.get('--root'));
const owned = resolve(options.get('--owned-root'));
const candidate = resolve(options.get('--candidate'));
const packageRoot = resolve(options.get('--pi-package'));
const qualificationSlice = options.get('--qualification-slice');
if (!['TRY_BRANCH_RESULTS', 'TRY_LOCAL_IDENTITIES'].includes(qualificationSlice)) throw new Error('UNKNOWN_QUALIFICATION_SLICE');
const publicContractVersion = qualificationSlice === 'TRY_BRANCH_RESULTS' ? 6 : 7;
const extension = join(candidate, 'share/kast/adapters/pi/extension.ts');
const rpc = join(candidate, 'bin/kast-tool-rpc');
const intent = JSON.parse(await readFile(options.get('--oracle'), 'utf8'));
if (!['fixture', 'supplemental', 'konditional'].includes(intent.scenario)) throw new Error('UNKNOWN_SCENARIO');
const output = join(owned, 'pi-qualification-' + (qualificationSlice === 'TRY_BRANCH_RESULTS' ? 'try-branch-results-' : '') + intent.scenario);
const expectedRoot = join(owned, intent.scenario === 'konditional' ? 'konditional-copy' : 'fixture');
const startPin = JSON.parse(await readFile(options.get('--start-pin'), 'utf8'));
stage = 'NATIVE_PIN_ADMISSION';
if (startPin.fixture.root !== root || startPin.cli.executable !== rpc || !Object.keys(startPin.plugin.native.classResources).length) throw new Error('NATIVE_PIN_MISMATCH');
if (startPin.qualificationSlice !== qualificationSlice || startPin.publicContractVersion !== publicContractVersion) throw new Error('NATIVE_PIN_SLICE_MISMATCH');
if (root !== expectedRoot || await realpath(root) !== root) throw new Error('OWNED_FIXTURE_MISMATCH');
const hash = buffer => createHash('sha256').update(buffer).digest('hex');
if (hash(await readFile(join(root, intent.sourcePath))) !== intent.sourceSha256) throw new Error('FIXTURE_CHANGED');
for (const file of intent.additionalSources) {
  if (hash(await readFile(join(root, file.file))) !== file.sha256) throw new Error('FIXTURE_CHANGED');
}
await mkdir(output, { mode: 0o700 });
process.env.KAST_TOOL_RPC_COMMAND = rpc;
process.env.JAVA_OPTS = '-Duser.home=' + join(owned, 'h');
process.env.KAST_INSTALL_IDEA_HOME = join(owned, 'IntelliJ IDEA.app/Contents');
process.env.IDEA_PROPERTIES = join(owned, 'idea.properties');
process.env.IDEA_VM_OPTIONS = join(owned, 'idea.vmoptions');
const moduleUrl = path => pathToFileURL(path).href;
stage = 'PI_RUNTIME_COMPOSITION';
const { createAgentSession, DefaultResourceLoader, ModelRuntime, SessionManager, SettingsManager } = await import(moduleUrl(join(packageRoot, 'dist/index.js')));
const { ReadOnlyAuthStorage } = await import(moduleUrl(join(packageRoot, 'dist/core/auth-storage.js')));
const { Type } = await import(moduleUrl(join(dirname(dirname(packageRoot)), 'typebox/build/index.mjs')));
const settings = SettingsManager.inMemory({ compaction: { enabled: false }, retry: { enabled: false }, cacheWarming: 'off' });
const modelRuntime = await ModelRuntime.create({
  credentials: new ReadOnlyAuthStorage(options.get('--auth')),
  modelsPath: null,
  modelsStorePath: join(output, 'models-cache.json'),
  allowModelNetwork: false,
  refreshOnCreate: false,
});
const model = modelRuntime.getModel('openai-codex', options.get('--model'));
if (!model) throw new Error('MODEL_UNAVAILABLE');
const calls = [];
let bytes = 0;
let executed = false;
let completed = false;
const grant = { maxElapsedMs: 20000, maxWorkUnits: 100000, maxResults: 32, maxReturnedBytes: 524288 };
const check = (condition, reason) => { if (!condition) throw new Error(reason); };
const loader = new DefaultResourceLoader({
  cwd: root, agentDir: join(owned, 'pi'), settingsManager: settings,
  noExtensions: true, noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true,
  additionalExtensionPaths: [extension],
  systemPrompt: 'Run qualification_probe exactly once. It invokes registered read-only Kast tools and performs its own deterministic sequence. Do not call other tools. After it completes, answer Done.',
  extensionFactories: [pi => pi.registerTool({
    name: 'qualification_probe', label: 'Read-only Kast qualification', description: 'Run the independently authored fixture investigation through registered query_symbols tools.',
    parameters: Type.Object({}, { additionalProperties: false }),
    async execute(_id, _params, signal, _update, ctx) {
      check(!executed, 'PROBE_REPLAY_REJECTED'); executed = true;
      let phase = 'BASELINE';
      async function invoke(request, expectedStale = false) {
        stage = 'REGISTERED_TOOL_EXECUTION';
        check(calls.length < 512 && !signal?.aborted, 'CALL_LIMIT_OR_CANCELLED');
        const arguments_ = { verbose: true, request };
        const outcome = await ctx.executeTool('query_symbols', arguments_, { signal });
        const content = outcome.result.content;
        check(!outcome.isError && content.length === 1 && content[0].type === 'text', 'PI_TOOL_EXECUTION_REJECTED');
        const reply = JSON.parse(content[0].text);
        const call = { tool: 'query_symbols', qualificationSlice, phase, arguments: arguments_, isError: outcome.isError, reply };
        bytes += Buffer.byteLength(JSON.stringify(call));
        check(bytes <= 32 * 1024 * 1024, 'RECEIPT_BYTE_LIMIT');
        calls.push(call);
        await writeFile(join(output, 'receipts.json'), JSON.stringify(calls, null, 2) + '\n', { mode: 0o600 });
        if (expectedStale) {
          check(reply.type === 'rejected_document' && reply.document.status === 'rejected' &&
            reply.document.rejection.type === 'reference-rejected' &&
            ['stale-authority', 'stale-generation', 'revalidation-content-changed'].includes(reply.document.rejection.reason),
            'EXPECTED_STALE_REFERENCE_REJECTION_NOT_OBSERVED');
          return reply.document;
        }
        check(['complete', 'qualified'].includes(reply.type), 'PRODUCT_REJECTION');
        return reply.document;
      }
      async function drain(request) {
        const pages = [];
        let page = await invoke(request);
        pages.push(page);
        for (let count = 0; page.continuation; count++) {
          check(count < 64, 'CONTINUATION_LIMIT');
          page = await invoke({ type: 'RESUME', continuation: page.continuation, executionBudget: grant });
          pages.push(page);
        }
        return pages;
      }
      async function locate(file, offset) {
        const pages = await drain({ type: 'RUN', source: { type: 'AT_LOCATION', file, offset },
          output: { type: 'SYMBOLS', fields: ['NAME', 'LOCATION', 'SIGNATURE'] }, executionBudget: grant });
        const symbols = pages.flatMap(page => page.items);
        check(symbols.length === 1 && symbols[0].type === 'exact-symbol', 'EXACT_LOOKUP_UNAVAILABLE');
        return symbols[0].ref;
      }
      const callables = new Map();
      for (const callable of intent.callables) {
        check(!callables.has(callable.producerPrefix), 'DUPLICATE_CALLEE_INTENT');
        callables.set(callable.producerPrefix, await locate(callable.declarationName.file, callable.declarationName.startInclusive));
      }
      const owners = new Map();
      for (const item of intent.flows) {
        const ownerKey = item.owner.file + ':' + item.owner.startInclusive;
        let enclosing = owners.get(ownerKey);
        if (!enclosing) { enclosing = await locate(item.owner.file, item.owner.startInclusive); owners.set(ownerKey, enclosing); }
        const selected = [...callables.entries()].filter(([prefix]) => item.producer.text.startsWith(prefix));
        check(selected.length === 1, 'CALLEE_INTENT_UNAVAILABLE');
        const pages = await drain({ type: 'RUN', source: { type: 'IMPACT', seeds: [{ enclosing,
          callable: selected[0][1],
          anchor: { start: item.producer.startInclusive, end: item.producer.endExclusive } }], declarations: [], models: [],
          domain: { type: 'SOURCE_DOMAIN', sourceSets: ['main'], directory: intent.sourceDirectory, sourcePolicy: 'PRODUCTION_ONLY', generatedSources: 'EXCLUDE' },
          flow: 'KOTLIN_FORWARD_V1' }, output: { type: 'VALUE_PATHS' }, retention: 'RETAIN', executionBudget: grant });
        const result = pages.at(-1).retention;
        check(result.kind === 'retained', 'RESULT_NOT_RETAINED');
        let cursor;
        const cursors = new Set();
        for (let count = 0; count < 64; count++) {
          const request = { type: 'READ_RESULT', result: result.reference,
            ...(cursor === undefined ? {} : { cursor }), output: { type: 'VALUE_PATHS' },
            executionBudget: { ...grant, maxResults: 1 } };
          const page = await invoke(request);
          const replay = await invoke(request);
          check(JSON.stringify(page.items) === JSON.stringify(replay.items) && page.next_cursor === replay.next_cursor,
            'RETAINED_PAGE_REPLAY_CHANGED');
          cursor = page.next_cursor;
          if (cursor === undefined || cursor === null) break;
          check(!cursors.has(cursor), 'RETAINED_CURSOR_REPEATED'); cursors.add(cursor);
          check(count < 63, 'RETAINED_PAGE_LIMIT');
        }
      }
      const localRefs = new Map();
      const localCases = qualificationSlice === 'TRY_LOCAL_IDENTITIES' ? intent.locals : [];
      for (const name of [...new Set(localCases.map(item => item.nameSite.text))]) {
        await drain({ type: 'RUN', source: { type: 'SEARCH_DECLARATIONS', declarationName: name,
          scope: { type: 'DIRECTORY', relativeDirectoryPath: intent.sourceDirectory, sourceSetNames: ['main'] } },
          output: { type: 'SYMBOLS', fields: ['NAME', 'LOCATION', 'SIGNATURE'] }, executionBudget: grant });
      }
      for (const item of localCases) {
        const ref = await locate(item.nameSite.file, item.nameSite.startInclusive);
        localRefs.set(item.name, ref);
        await drain({ type: 'RUN', source: { type: 'SYMBOL_REFS', symbolRefs: [ref] },
          output: { type: 'SYMBOLS', fields: ['NAME', 'LOCATION', 'SIGNATURE', 'SOURCE'] }, executionBudget: grant });
        await drain({ type: 'RUN', source: { type: 'SYMBOL_REFS', symbolRefs: [ref] },
          steps: [{ type: 'EXPAND_RELATION', relation: 'REFERENCES', expansionScope: { type: 'SOURCE_DOMAIN',
            sourceSets: ['main'], directory: intent.sourceDirectory, sourcePolicy: 'PRODUCTION_ONLY', generatedSources: 'EXCLUDE' } }],
          output: { type: 'OCCURRENCES' }, retention: 'RETAIN', executionBudget: grant });
      }
      if (qualificationSlice === 'TRY_LOCAL_IDENTITIES' && intent.scenario === 'fixture') {
        stage = 'OWNED_STALE_REFERENCE_MUTATION';
        const item = intent.locals.find(item => item.name === 'outer-val');
        check(item && localRefs.has(item.name), 'LOCAL_STALE_CASE_UNAVAILABLE');
        const sourceFile = join(root, item.declaration.file);
        check(await realpath(sourceFile) === sourceFile, 'OWNED_SOURCE_MISMATCH');
        const original = await readFile(sourceFile);
        check(hash(original) === intent.sourceSha256, 'FIXTURE_CHANGED_BEFORE_MUTATION');
        const source = original.toString('utf8');
        const declaration = source.slice(item.declaration.startInclusive, item.declaration.endExclusive);
        check(declaration === 'val binding = Voltage.encrypt(input)', 'STABLE_OFFSET_CASE_UNAVAILABLE');
        const replacement = declaration.replace('encrypt', 'decrypt');
        const changed = Buffer.from(source.slice(0, item.declaration.startInclusive) + replacement + source.slice(item.declaration.endExclusive));
        check(changed.length === original.length && hash(changed) !== hash(original), 'CONTROLLED_MUTATION_UNAVAILABLE');
        const environment = JSON.parse(await readFile(join(owned, 'environment.json'), 'utf8'));
        const launcher = join(owned, 'IntelliJ IDEA.app/Contents/MacOS/idea');
        const template = await readFile(new URL('./try-local-source-refresh.kts.template', import.meta.url), 'utf8');
        async function refresh(label, expected) {
          stage = 'OWNED_NATIVE_SOURCE_REFRESH';
          const host = startPin.host;
          check(host.pid !== environment.protectedPid && Number.isSafeInteger(host.pid), 'OWNED_HOST_UNAVAILABLE');
          const running = await execute('/bin/ps', ['-p', String(host.pid), '-o', 'comm='], { timeout: 5000, maxBuffer: 65536 });
          check(running.stdout.trim() === launcher, 'OWNED_HOST_UNAVAILABLE');
          const receipt = join(output, label + '-refresh.json');
          const inputPath = join(output, label + '-refresh-input.json');
          await writeFile(inputPath, JSON.stringify({ ownedRoot: owned, projectRoot: root, source: sourceFile,
            expectedSha256: expected, hostPid: host.pid, protectedPid: environment.protectedPid,
            processStart: host.processStart, receipt }), { mode: 0o600 });
          const script = join(output, label + '-refresh.kts');
          await writeFile(script, template.replace('@INPUT_BASE64@', Buffer.from(inputPath).toString('base64')), { mode: 0o600 });
          await execute(launcher, ['ideScript', script], { cwd: root, env: process.env, timeout: 45000, maxBuffer: 65536 });
          let result;
          for (let attempts = 0; attempts < 180; attempts++) {
            try { result = JSON.parse(await readFile(receipt, 'utf8')); break; }
            catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
            await new Promise(resolve => setTimeout(resolve, 250));
          }
          check(result?.type === 'SOURCE_READY' && result.hostPid === host.pid && result.processStart === host.processStart &&
            result.projectRoot === root && result.source === sourceFile && result.saved && result.committed && result.smart &&
            ['diskSha256', 'vfsSha256', 'documentSha256', 'psiSha256'].every(key => result[key] === expected),
            'OWNED_REFRESH_UNCONFIRMED');
        }
        try {
          await writeFile(sourceFile, changed);
          await refresh('changed', hash(changed));
          phase = 'STALE_REFERENCE';
          await invoke({ type: 'RUN', source: { type: 'SYMBOL_REFS', symbolRefs: [localRefs.get(item.name)] },
            output: { type: 'SYMBOLS', fields: ['NAME', 'LOCATION', 'SIGNATURE'] }, executionBudget: grant }, true);
          phase = 'REDISCOVERY';
          const current = await locate(item.nameSite.file, item.nameSite.startInclusive);
          check(current !== localRefs.get(item.name), 'LOCAL_REFERENCE_REBOUND_WITHOUT_REDISCOVERY');
          await invoke({ type: 'RUN', source: { type: 'SYMBOL_REFS', symbolRefs: [current] },
            output: { type: 'SYMBOLS', fields: ['NAME', 'LOCATION', 'SIGNATURE'] }, executionBudget: grant });
        } finally {
          await writeFile(sourceFile, original);
          await refresh('restored', hash(original));
          check(hash(await readFile(sourceFile)) === intent.sourceSha256, 'OWNED_SOURCE_RESTORE_UNCONFIRMED');
        }
        await writeFile(join(output, 'source-mutation.json'), JSON.stringify({ type: 'OWNED_STALE_REFERENCE_CASE',
          source: sourceFile, beforeSha256: hash(original), changedSha256: hash(changed), restoredSha256: hash(await readFile(sourceFile)),
          declarationStart: item.declaration.startInclusive, declarationEnd: item.declaration.endExclusive,
          nameAndRangePreserved: true, mutation: 'SAME_LENGTH_INITIALIZER_CALL_CHANGE', restored: true }), { mode: 0o600 });
      }
      completed = true;
      return { content: [{ type: 'text', text: 'Captured ' + calls.length + ' registered read-only tool calls. Independent receipt checks and end native pin remain required.' }] };
    },
  })],
});
stage = 'EXTENSION_REGISTRATION';
await loader.reload();
const { session } = await createAgentSession({ cwd: root, agentDir: join(owned, 'pi'), modelRuntime, model,
  settingsManager: settings, sessionManager: SessionManager.inMemory(), resourceLoader: loader,
  tools: ['qualification_probe', 'query_symbols'], thinkingLevel: 'high' });
const dispatches = [];
const toolEvents = [];
let eventFailure = false;
const unsubscribe = session.subscribe(event => {
  if (event.type === 'message_end' && event.message.role === 'assistant') {
    for (const item of event.message.content) {
      if (item.type !== 'toolCall') continue;
      if (dispatches.length >= 4 || item.name !== 'qualification_probe' || typeof item.id !== 'string' || item.id.length > 256) {
        eventFailure = true; continue;
      }
      dispatches.push({ type: 'ASSISTANT_TOOL_DISPATCH', toolCallId: item.id, toolName: item.name });
    }
  }
  if (event.type === 'tool_execution_start' || event.type === 'tool_execution_end') {
    if (toolEvents.length >= 1026 || !['qualification_probe', 'query_symbols'].includes(event.toolName)) {
      eventFailure = true; return;
    }
    toolEvents.push({ type: event.type, toolCallId: event.toolCallId, toolName: event.toolName,
      ...(event.parentToolCallId === undefined ? {} : { parentToolCallId: event.parentToolCallId }),
      ...(event.type === 'tool_execution_end' ? { isError: event.isError } : {}) });
  }
});
const timer = setTimeout(() => session.abort(), 600000);
try {
  await session.bindExtensions({});
  stage = 'MODEL_PROBE_DISPATCH';
  await session.prompt('Run qualification_probe now, exactly once.');
  check(completed, 'PROBE_DID_NOT_COMPLETE');
  await writeFile(join(output, 'pi-events.json'), JSON.stringify({ dispatches, toolEvents }, null, 2) + '\n', { mode: 0o600 });
  check(!eventFailure && dispatches.length === 1, 'MODEL_REGISTERED_DISPATCH_UNCONFIRMED');
  const probeId = dispatches[0].toolCallId;
  const nested = toolEvents.filter(event => event.toolName === 'query_symbols');
  check(nested.length === calls.length * 2 && nested.every(event => event.parentToolCallId === probeId) &&
    nested.filter(event => event.type === 'tool_execution_end').every(event => !event.isError),
    'REGISTERED_TOOL_PIPELINE_UNCONFIRMED');
  await writeFile(join(output, 'transport.json'), JSON.stringify({
    type: 'PI_REGISTERED_TOOL_CAPTURE', qualificationSlice, publicContractVersion, calls: calls.length, root, executable: rpc,
    executableSha256: hash(await readFile(rpc)), extensionSha256: hash(await readFile(extension)),
    piPackageSha256: hash(await readFile(join(packageRoot, 'package.json'))), model: model.id,
    nativePin: resolve(options.get('--start-pin')), nativePinSha256: hash(await readFile(options.get('--start-pin'))),
    eventReceiptSha256: hash(await readFile(join(output, 'pi-events.json'))),
    assertionStatus: 'UNVERIFIED',
  }, null, 2) + '\n', { mode: 0o600 });
  console.log(JSON.stringify({ type: 'PI_CAPTURED_REQUIRES_ASSERTIONS_AND_END_PIN', calls: calls.length, output }));
} finally {
  clearTimeout(timer);
  unsubscribe();
  session.dispose();
}
}

try {
  await main();
} catch {
  // Provider/engine exceptions can contain private context. Retain no arbitrary error payload.
  console.error(JSON.stringify({ type: 'PI_CAPTURE_FAILED', stage, outcome: 'REQUIRED_BOUNDARY_UNCONFIRMED' }));
  process.exitCode = 1;
}
