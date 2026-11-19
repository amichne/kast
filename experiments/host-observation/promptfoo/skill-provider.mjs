import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { access, cp, lstat, mkdir, mkdtemp, readFile, readdir, realpath, rename, rm, stat, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { delimiter, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodeReplayCredential } from '../pi_evaluation_replay_auth.mjs';
import { loadKastGuidance, stageKastGuidance } from './kast-guidance.mjs';
import { armCapabilities, completionFile, guidedCases, invocationFile, originalCases, skillCases } from './skill-cases.mjs';
import { runtimeEnvironment } from './skill-sandbox.mjs';
import { observePiTrial, projectPiTrial, snapshotPiCatalog } from './pi-accounting.mjs';
import { admitParticipantSurface, createParticipantDirectory } from './participant-surface.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const fixture = fileURLToPath(new URL('../static-callback-fixture/', import.meta.url));
const tool = fileURLToPath(new URL('./skill-tool.ts', import.meta.url));
const guidanceTool = fileURLToPath(new URL('./kast-guidance-tool.ts', import.meta.url));
const kastTools = new Set(['kast', 'read_previous_result', 'query_imported_main']);
const snapshots = new URL('./history/pi-skill-cli-snapshots.json', import.meta.url);
const neutral = 'You are a coding assistant. Use the tools available in this session when needed to answer the task.';
export { neutral as neutralSystem };
const maximumBytes = 16 * 1024 * 1024;
const totalHash = input => createHash('sha256').update(input).digest('hex');
const fixtureQuery = ['query', '--name', 'invokeCallback', '--in', 'invocation', '--kind', 'function', '--source-set', 'main'];
const defaultModel = 'openai-codex/gpt-6.1-sol';
const eventPrefix = Symbol('parsed Pi event prefix');

export async function binary(override, name) {
  const candidates = override ? [override] : (process.env.PATH ?? '').split(delimiter)
    .filter(isAbsolute).map(directory => join(directory, name));
  for (const candidate of candidates) {
    try {
      await access(resolve(candidate), constants.X_OK);
      return await realpath(candidate);
    } catch {}
  }
  throw Error(`Set the executable path for ${name}`);
}

export function child(command, args, options) {
  return new Promise((resolveResult, reject) => {
    const env = options.env ?? runtimeEnvironment();
    const ownedGroup = options.captureLimits === true && process.platform !== 'win32';
    const proc = spawn(command, args, { cwd: options.cwd, env, detached: ownedGroup,
      stdio: [options.input ? 'pipe' : 'ignore', 'pipe', 'pipe'] });
    const stdout = [];
    let bytes = 0;
    let termination, reapTimer;
    const timer = setTimeout(() => stop('PARTICIPANT_DEADLINE'), options.timeout);
    const interrupt = () => stop('OWNER_INTERRUPTED');
    if (ownedGroup) {
      process.once('SIGINT', interrupt);
      process.once('SIGTERM', interrupt);
    }
    function dispose() {
      clearTimeout(timer);
      clearTimeout(reapTimer);
      if (ownedGroup) {
        process.removeListener('SIGINT', interrupt);
        process.removeListener('SIGTERM', interrupt);
      }
    }
    function killGroup() {
      try { process.kill(-proc.pid, 'SIGKILL'); }
      catch (error) { if (error.code !== 'ESRCH') throw error; }
    }
    function stop(reason) {
      if (termination) return;
      termination = reason;
      clearTimeout(timer);
      reapTimer = setTimeout(() => {
        dispose();
        proc.stdout.destroy();
        proc.stderr.destroy();
        proc.unref();
        reject(Object.assign(Error('Owned process did not settle after cancellation'), { code: 'PI_CHILD_REAP_UNAVAILABLE' }));
      }, 2000);
      try {
        if (ownedGroup && proc.pid) killGroup();
        else proc.kill('SIGKILL');
      } catch (error) {
        if (error.code !== 'ESRCH') return;
      }
    }
    proc.stdout.on('data', chunk => {
      const allowed = Math.min(maximumBytes - bytes, chunk.length);
      if (allowed > 0) stdout.push(chunk.subarray(0, allowed));
      bytes += allowed;
      if (allowed < chunk.length) stop('PARTICIPANT_OUTPUT_LIMIT');
    });
    proc.stderr.resume();
    proc.once('error', error => {
      dispose();
      reject(error);
    });
    proc.once('close', status => {
      dispose();
      if (ownedGroup && proc.pid) {
        // A timed-out tool may leave a descendant after Pi has exited.
        try { killGroup(); }
        catch { reject(Object.assign(Error('Owned process group did not settle'), { code: 'PI_CHILD_REAP_UNAVAILABLE' })); return; }
      }
      if (termination && !options.captureLimits) {
        reject(Error(termination === 'PARTICIPANT_DEADLINE' ? 'Process exceeded the trial time limit' : 'Process exceeded the trial output limit'));
      } else {
        resolveResult({ status, stdout: Buffer.concat(stdout).toString('utf8'), ...(termination && { termination }) });
      }
    });
    if (options.input) {
      proc.stdin.on('error', () => {});
      proc.stdin.end(options.input);
    }
  });
}

async function credentials(agentDirectory, expectedModel) {
  const defaultAgent = join(homedir(), '.pi', 'agent');
  const piAuth = process.env.KAST_EVAL_PI_AUTH ?? join(defaultAgent, 'auth.json');
  const type = process.env.KAST_EVAL_CODEX_AUTH ? 'CODEX_CHATGPT' : 'PI_OAUTH';
  let document;
  try {
    document = await readFile(process.env.KAST_EVAL_CODEX_AUTH ?? piAuth, 'utf8');
  } catch {
    throw Error('Supply a signed-in Pi credential with KAST_EVAL_PI_AUTH or a Codex credential with KAST_EVAL_CODEX_AUTH');
  }
  const admitted = decodeReplayCredential(document, type, Date.now());
  if (admitted.type !== 'ready') throw Error(`Pi credential rejected: ${admitted.reason}`);
  await writeFile(join(agentDirectory, 'auth.json'), JSON.stringify({ 'openai-codex': admitted.credential }), { mode: 0o600 });
  const catalogPath = process.env.KAST_EVAL_PI_MODELS ?? join(defaultAgent, 'models-store.json');
  const catalog = await readFile(catalogPath, 'utf8').catch(error => {
    if (error.code === 'ENOENT' && !process.env.KAST_EVAL_PI_MODELS) return '{}';
    throw Error('Pi model catalog unavailable');
  });
  const models = JSON.parse(catalog)['openai-codex'];
  const copiedCatalog = models ? JSON.stringify({ 'openai-codex': models }) : undefined;
  if (models) {
    await writeFile(join(agentDirectory, 'models-store.json'), copiedCatalog, { mode: 0o600 });
  }
  await writeFile(join(agentDirectory, 'settings.json'), JSON.stringify({
    transport: 'sse', cacheWarming: 'off', compaction: { enabled: false },
    retry: { enabled: false, maxRetries: 0, provider: { maxRetries: 0 } },
    enableInstallTelemetry: false,
  }), { mode: 0o600 });
  return snapshotPiCatalog(copiedCatalog, expectedModel);
}

export function decodePiTrial(stdout, expectedModel, toolNames, requireNormalStop = true, receipt = {}) {
  return decodePiEvents(parsePiEvents(stdout), expectedModel, toolNames, requireNormalStop, false, receipt);
}

function parsePiEvents(stdout) {
  const events = [];
  try {
    for (const line of stdout.split('\n').filter(line => line.trim())) {
      const event = JSON.parse(line);
      if (typeof event?.type !== 'string') throw Error('Missing Pi event type');
      events.push(event);
    }
    return events;
  } catch {
    throw Object.assign(Error('Pi event stream could not be decoded'), { code: 'PI_EVENT_PARSE_FAILED', [eventPrefix]: events });
  }
}

function terminalAssistant(events) {
  return events.findLast(event => event.type === 'message_end' && event.message?.role === 'assistant')?.message;
}

function terminalCause(terminal, status) {
  if (!terminal) return 'PI_TERMINAL_MISSING';
  if (terminal.stopReason === 'aborted') return 'PI_PROVIDER_ABORTED';
  if (terminal.stopReason === 'error') {
    const usageLimit = terminal.provider === 'openai-codex' && typeof terminal.errorMessage === 'string' &&
      /^You have hit your ChatGPT usage limit(?: \([a-z]+ plan\))?\./.test(terminal.errorMessage);
    return usageLimit ? 'PI_PROVIDER_USAGE_LIMIT' : 'PI_PROVIDER_ERROR';
  }
  if (terminal.stopReason !== 'stop') return 'PI_ABNORMAL_STOP';
  return status === 0 ? 'PI_EVENT_EVIDENCE_REJECTED' : 'PI_NONZERO_EXIT';
}

function decodePiEvents(events, expectedModel, toolNames, requireNormalStop, permitMissingUsage = false, receipt = {}) {
  const assistants = events.filter(event => event.type === 'message_end' && event.message?.role === 'assistant')
    .map(event => event.message);
  const terminal = assistants.at(-1);
  if (requireNormalStop && terminal?.stopReason !== 'stop') throw Error('Pi did not finish normally');
  for (const message of assistants) {
    if (`${message.provider}/${message.model}` !== expectedModel) throw Error('Pi used a different model');
  }
  const { usage, completeUsage, invalidUsage, assistantMessages, ...observation } = observePiTrial(events, receipt);
  if (invalidUsage && !permitMissingUsage) throw Error('Pi did not report complete consistent token usage');
  if (requireNormalStop && usage.total <= 0) throw Error('Pi did not report model usage');
  const starts = events.filter(event => event.type === 'tool_execution_start');
  const ends = events.filter(event => event.type === 'tool_execution_end');
  if ([...starts, ...ends].some(event => !toolNames.includes(event.toolName))) throw Error('Unexpected Pi tool');
  const pending = new Map();
  for (const event of starts) {
    if (pending.has(event.toolCallId)) throw Error('Duplicate Pi tool call');
    pending.set(event.toolCallId, event.toolName);
  }
  for (const event of ends) {
    if (pending.get(event.toolCallId) !== event.toolName) throw Error('Unmatched Pi tool result');
    pending.delete(event.toolCallId);
  }
  if (requireNormalStop && pending.size) throw Error('Pi lost a tool result');
  const results = new Map(ends.map(event => [event.toolCallId, event.result?.details]));
  const calls = starts.map(event => ({
    tool: event.toolName, argv: event.args?.argv, input: event.args?.stdin, ...results.get(event.toolCallId),
  }));
  const reply = terminal?.content?.filter(part => part.type === 'text').map(part => part.text).join('').trim();
  const toolProposals = assistantMessages.reduce((total, message) => total + (message.content?.filter(part => part.type === 'toolCall').length ?? 0), 0);
  return { reply, calls, usage, toolCalls: starts.length, toolProposals, ...observation,
    ...(permitMissingUsage && { completeUsage }) };
}

function partialTrial(result, expectedModel, toolNames, receipt) {
  if (result.termination && !['PARTICIPANT_DEADLINE', 'PARTICIPANT_OUTPUT_LIMIT'].includes(result.termination)) return null;
  const prefix = result.termination ? result.stdout.slice(0, result.stdout.lastIndexOf('\n') + 1) : result.stdout;
  const events = parsePiEvents(prefix);
  const terminal = terminalAssistant(events);
  if (['error', 'aborted'].includes(terminal?.stopReason)) return null;
  const termination = result.termination ?? (result.status === 0 && terminal?.stopReason === 'length' ? 'MODEL_OUTPUT_LIMIT' : null);
  if (!termination) return null;
  const assistant = [];
  const openText = new Map();
  let opened = false;
  let active = false;
  let openToolCalls = 0;
  for (const event of events) {
    if (event.type === 'message_start' && event.message?.role === 'assistant') {
      if (`${event.message.provider}/${event.message.model}` !== expectedModel) throw Error('Pi used a different model');
      opened = true;
      active = true;
      openText.clear();
      openToolCalls = 0;
    } else if (event.type === 'message_end' && event.message?.role === 'assistant') {
      opened = true;
      active = false;
      const text = event.message.content?.filter(part => part.type === 'text').map(part => part.text).join('').trim();
      if (text) assistant.push(text);
    } else if (active && event.type === 'message_update') {
      const update = event.assistantMessageEvent;
      if (update?.type === 'text_delta' && typeof update.delta === 'string') {
        openText.set(update.contentIndex, (openText.get(update.contentIndex) ?? '') + update.delta);
      } else if (update?.type === 'toolcall_start') openToolCalls++;
    }
  }
  if (!opened) return null;
  if (active) {
    const pending = [...openText.entries()].sort(([left], [right]) => left - right).map(([, text]) => text).join('').trim();
    if (pending) assistant.push(pending);
  }
  const { usage, completeUsage, ...trial } = decodePiEvents(events, expectedModel, toolNames, false, true,
    { ...receipt, unsettled: termination !== 'MODEL_OUTPUT_LIMIT' });
  const finalizedUsage = usage.total > 0 ? usage : null;
  const observableCalls = trial.toolProposals + (active ? openToolCalls : 0);
  const transcript = assistant.slice(-8).map(text => text.slice(0, 16_384));
  const tools = trial.calls.slice(-64).map(({ tool, reason, status, complete }) => ({ tool, reason, status, complete }));
  return { ...trial, reply: assistant.at(-1), termination, toolProposals: observableCalls,
    usage: termination === 'MODEL_OUTPUT_LIMIT' && completeUsage ? finalizedUsage : null, finalizedUsage,
    partialTranscript: { assistant: transcript, tools,
      truncated: assistant.length > transcript.length || assistant.slice(-8).some(text => text.length > 16_384) || tools.length < trial.calls.length } };
}

export function settlePiTrial(result, model, toolNames, captureLimits = false, receipt = {}) {
  try { return settlePiResult(result, model, toolNames, captureLimits, receipt); }
  catch (error) {
    let events;
    try { events = parsePiEvents(result.stdout); }
    catch (parseError) { events = parseError[eventPrefix] ?? []; }
    const { usage, completeUsage, invalidUsage, assistantMessages, ...observation } =
      observePiTrial(events, { ...receipt, unsettled: true });
    error.trial = { ...observation, usage: null, ...(usage.total > 0 && { finalizedUsage: usage }),
      error: error.message, terminalCause: error.code ?? 'PI_EVENT_EVIDENCE_REJECTED' };
    throw error;
  }
}

function settlePiResult(result, model, toolNames, captureLimits, receipt) {
  if (captureLimits) {
    if (result.termination === 'OWNER_INTERRUPTED') {
      throw Object.assign(Error('Pi trial interrupted by the owner'), { code: 'PI_OWNER_INTERRUPTED' });
    }
    const bounded = partialTrial(result, model, toolNames, receipt);
    if (bounded) return bounded;
    if (result.termination) {
      throw Object.assign(Error('Pi did not supply admissible participant evidence before the cutoff'), {
        code: 'PI_CUTOFF_EVIDENCE_UNAVAILABLE',
      });
    }
  }
  const events = parsePiEvents(result.stdout);
  const terminal = terminalAssistant(events);
  try {
    const trial = decodePiEvents(events, model, toolNames, true, false, receipt);
    return { ...trial, ...(result.status !== 0 && {
      error: `Pi exited with status ${result.status}`, terminalCause: terminalCause(terminal, result.status),
    }) };
  } catch (error) {
    try {
      const providerStopped = ['error', 'aborted'].includes(terminal?.stopReason);
      const { completeUsage, ...incomplete } = decodePiEvents(events, model, toolNames, false, providerStopped, receipt);
      return { ...incomplete, ...(completeUsage === false && { usage: null,
        finalizedUsage: incomplete.usage.total > 0 ? incomplete.usage : null }),
        error: result.status === 0 ? error.message : `Pi exited with status ${result.status}`,
        terminalCause: terminalCause(terminal, result.status) };
    } catch {
      throw Object.assign(Error('Pi event evidence was rejected'), { code: 'PI_EVENT_EVIDENCE_REJECTED' });
    }
  }
}

export async function runIsolatedPi({
  directory, prompt, system = neutral, model = defaultModel, effort = 'low', extension, extensionEnvironment = {}, toolNames = [], timeout = 150_000,
  captureLimits = false, guidance, inlineSkill, toolUsagePolicy = 'unknown', role = 'participant', visiblePaths = [],
}) {
  if (!['unknown', 'no-model-helpers'].includes(toolUsagePolicy)) throw Error('Unknown Pi tool usage policy');
  if (inlineSkill !== undefined && (guidance || typeof inlineSkill !== 'string' || !inlineSkill.trim())) throw Error('Invalid inline skill');
  if (!guidance && !inlineSkill && toolNames.some(name => kastTools.has(name))) throw Error('Kast access needs participant guidance');
  // Blinding precedes every effect: no binary lookup, credential copy or model
  // process happens for a participant who could observe the measurement. Pi
  // renders the working directory into its system prompt, and Kast replies carry
  // workspace roots, so both are part of the observed surface.
  const participantSurface = admitParticipantSurface({ role, system, prompt, directory, visiblePaths, toolNames });
  if (participantSurface.state === 'REJECTED') {
    // The partial trial holds no model evidence; it preserves the typed findings
    // through every provider's existing rejected-attempt projection.
    throw Object.assign(Error('PARTICIPANT_SURFACE_REJECTED'), {
      code: 'PARTICIPANT_SURFACE_REJECTED', participantSurface, trial: { participantSurface },
    });
  }
  const pi = await binary(process.env.KAST_EVAL_PI, 'pi');
  const agent = join(directory, 'pi');
  await mkdir(agent, { mode: 0o700 });
  const catalog = await credentials(agent, model);
  const runtime = { executable: { sha256: await hashBinary(pi), version: 'unknown' }, catalog,
    requested: { model, effort }, sourceRevision: 'unknown' };
  if (guidance) await stageKastGuidance(directory, guidance);
  const instructions = guidance ? `${system}\n\n${guidance.text} Use read_kast_guidance to open it.` : [system, inlineSkill].filter(Boolean).join('\n\n');
  const systemFile = join(directory, 'system-prompt.txt');
  await writeFile(systemFile, instructions, { mode: 0o600 });
  const args = [
    '--offline', '--no-session', '--no-context-files', '--no-extensions', '--no-skills', '--no-mcp',
    '--no-prompt-templates', '--no-themes', '--no-approve', '--no-builtin-tools',
    ...(extension ? ['-e', extension] : []), ...(guidance ? ['-e', guidanceTool] : []),
    '--model', model, '--thinking', effort,
    '--system-prompt', systemFile, '--mode', 'json', '-p', '--', prompt,
  ];
  const started = performance.now();
  const result = await child(pi, args, {
    cwd: directory, timeout, captureLimits,
    env: { ...runtimeEnvironment(), ...extensionEnvironment, PI_CODING_AGENT_DIR: agent, PI_OFFLINE: '1' },
  });
  const admittedTools = guidance ? [...toolNames, 'read_kast_guidance'] : toolNames;
  let trial;
  try { trial = settlePiTrial(result, model, admittedTools, captureLimits, { runtime, toolUsagePolicy }); }
  catch (error) {
    if (error.trial) Object.assign(error.trial, { elapsedMillis: performance.now() - started, participantSurface });
    throw error;
  }
  return {
    ...trial,
    participantSurface,
    elapsedMillis: performance.now() - started,
    systemSha256: totalHash(instructions),
    ...(guidance && { guidance: { skillSha256: guidance.skillSha256, guideSha256: guidance.guideSha256 } }),
  };
}

async function fixtureFiles(directory, includes, label) {
  const files = (await readdir(directory, { recursive: true, withFileTypes: true })).filter(file => includes(file.name));
  if (files.some(file => !file.isFile())) throw Error(`Fixture ${label} paths must be regular files`);
  const entries = await Promise.all(files.map(async entry => {
    const path = join(entry.parentPath, entry.name);
    return [relative(directory, path), totalHash(await readFile(path))];
  }));
  return Object.fromEntries(entries.sort(([left], [right]) => left.localeCompare(right)));
}

async function validateFixtureKotlin(workspace) {
  const includes = name => name.endsWith('.kt');
  const expected = await fixtureFiles(fixture, includes, 'Kotlin');
  const actual = await fixtureFiles(workspace, includes, 'Kotlin');
  if (!(invocationFile in expected) || JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw Error('Static callback workspace Kotlin sources differ from the fixture');
  }
}

async function validateManagedFixture(workspace) {
  await validateFixtureKotlin(workspace);
  const includes = name => name.endsWith('.gradle') || name.endsWith('.gradle.kts');
  const expected = await fixtureFiles(fixture, includes, 'Gradle');
  const actual = await fixtureFiles(workspace, includes, 'Gradle');
  if (!('settings.gradle.kts' in expected) || JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw Error('Static callback workspace Gradle scripts differ from the fixture');
  }
  const wrapper = ['gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties'];
  for (const file of wrapper) {
    const copy = join(workspace, file);
    if (!(await lstat(copy)).isFile() || !(await readFile(copy)).equals(await readFile(join(root, file)))) {
      throw Error(`Static callback workspace wrapper differs from the repository (${file})`);
    }
  }
  await access(join(workspace, 'gradlew'), constants.X_OK);
}

async function workspaceDirectoryExists(path) {
  try {
    if (!(await lstat(path)).isDirectory()) throw Error(`Expected a workspace directory at ${path}`);
    return true;
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

export async function prepareFixtureWorkspace(directory) {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const parent = await realpath(directory);
  const workspace = join(parent, 'callback-service');
  if (!(await workspaceDirectoryExists(workspace))) {
    const staging = await mkdtemp(join(parent, '.callback-service-'));
    try {
      await cp(fixture, staging, { recursive: true });
      await cp(join(root, 'gradle/wrapper'), join(staging, 'gradle/wrapper'), { recursive: true });
      await cp(join(root, 'gradlew'), join(staging, 'gradlew'));
      const initialized = await child('git', ['init', '-q'], { cwd: staging, timeout: 10_000 });
      if (initialized.status !== 0) throw Error('Could not initialize the static callback workspace');
      await validateManagedFixture(staging);
      try {
        await rename(staging, workspace);
      } catch (error) {
        if (!['EEXIST', 'ENOTEMPTY'].includes(error.code) || !(await workspaceDirectoryExists(workspace))) throw error;
      }
    } finally {
      await rm(staging, { recursive: true, force: true });
    }
  }
  await validateManagedFixture(workspace);
  return workspace;
}

/** A trial-owned copy of the fixture without a Gradle wrapper; the caller stops its Host and removes it. */
export async function prepareUnwrappedWorkspace() {
  const parent = await realpath(await mkdtemp(join(tmpdir(), 'kast-unwrapped-')));
  const workspace = join(parent, 'callback-service');
  try {
    await cp(fixture, workspace, { recursive: true });
    const initialized = await child('git', ['init', '-q'], { cwd: workspace, timeout: 10_000 });
    if (initialized.status !== 0) throw Error('Could not initialize the unwrapped workspace');
    await validateFixtureKotlin(workspace);
    if (await workspaceDirectoryExists(join(workspace, 'gradle'))) throw Error('Unwrapped workspace has a Gradle wrapper');
    return { root: workspace, owned: parent };
  } catch (error) {
    await rm(parent, { recursive: true, force: true });
    throw error;
  }
}

export async function prepareWorkspace(scenario, directory) {
  if (!scenario.workspace) return { root: directory };
  if (scenario.workspace === 'unwrapped') return prepareUnwrappedWorkspace();
  if (scenario.workspace === 'repository') {
    const workspace = await realpath(process.env.KAST_EVAL_QCD_WORKSPACE ?? root);
    if (!(await stat(join(workspace, completionFile))).isFile()) throw Error('QueryCompletionDocument source unavailable');
    return { root: workspace };
  }
  const configured = process.env.KAST_EVAL_WORKSPACE;
  if (!configured) return { root: await prepareFixtureWorkspace(join(root, 'build/projects')) };
  const workspace = await realpath(configured);
  await validateFixtureKotlin(workspace);
  return { root: workspace };
}

async function warmWorkspace(workspace, kast, mode) {
  const args = mode === 'repository'
    ? ['query', '--name', 'QueryCompletionDocument', '--in', 'protocol', '--kind', 'class', '--source-set', 'main']
    : fixtureQuery;
  const result = await child(kast, args, { cwd: workspace, timeout: 900_000 });
  let document;
  try { document = JSON.parse(result.stdout); } catch {}
  if (result.status !== 0 || document?.type !== 'complete' || document.document?.coverage?.exhaustive !== true) {
    throw Error(`Kast workspace warmup did not produce complete evidence (${document?.type ?? 'invalid response'})`);
  }
}

async function hashBinary(path) {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(path)) digest.update(chunk);
  return digest.digest('hex');
}

async function guidanceFor(capabilities) {
  if (!capabilities.kast) return undefined;
  if (!capabilities.snapshot) return loadKastGuidance();
  const files = JSON.parse(await readFile(snapshots, 'utf8'))[capabilities.snapshot];
  return loadKastGuidance({ skillText: files['SKILL.md'], guideText: files['public-cli.md'] });
}

export default class SkillProvider {
  id() { return 'pi-kast-skill'; }

  async callApi(prompt, context) {
    const { caseId, arm, comparison } = context.vars;
    const capabilities = armCapabilities[arm];
    const scenario = skillCases[caseId];
    if (!Object.hasOwn(skillCases, caseId) || !Object.hasOwn(armCapabilities, arm) || capabilities.comparison !== comparison ||
        guidedCases.has(caseId) !== (comparison === 'guided-kast-access') ||
        (comparison === 'skill-version' && !originalCases.includes(caseId)) || prompt !== scenario.prompt) {
      return { error: 'Invalid skill evaluation case or arm' };
    }
    const directory = await createParticipantDirectory();
    let kast;
    let workspace;
    try {
      const draftBinary = arm === 'reference' ? process.env.KAST_EVAL_REFERENCE_KAST :
        arm === 'delivered' ? process.env.KAST_EVAL_DELIVERED_DRAFT_KAST : undefined;
      kast = await binary((!scenario.workspace && draftBinary) || process.env.KAST_EVAL_KAST, 'kast');
      workspace = await prepareWorkspace(scenario, directory);
      if (scenario.workspace && !workspace.owned) await warmWorkspace(workspace.root, kast, scenario.workspace);
      const guidance = await guidanceFor(capabilities);
      const model = process.env.KAST_EVAL_PI_MODEL ?? defaultModel;
      const trial = await runIsolatedPi({
        directory, prompt, guidance, model, extension: tool, visiblePaths: [workspace.root],
        toolUsagePolicy: 'no-model-helpers',
        extensionEnvironment: {
          KAST_EVAL_TRIAL_SCOPE: scenario.workspace ?? 'draft',
          KAST_EVAL_TRIAL_WORKSPACE: workspace.root,
          ...(capabilities.kast ? { KAST_EVAL_TRIAL_BIN: kast } : {}),
          ...(capabilities.read ? { KAST_EVAL_TRIAL_READER: '1' } : {}),
        },
        toolNames: [
          ...(capabilities.kast ? ['kast'] : []), ...(capabilities.read ? ['search_kotlin_source'] : []),
        ],
      });
      const expectedDraft = !scenario.workspace && trial.reply && !trial.error;
      let planned;
      if (expectedDraft && ['select', 'kind', 'walk', 'callees'].includes(caseId)) {
        const { planSkillDraft } = await import('./skill-grading.mjs');
        planned = await planSkillDraft(caseId, trial.reply, (args, input) =>
          child(kast, ['query', 'plan', ...args], { cwd: workspace.root, timeout: 10_000, input }));
      }
      const projected = projectPiTrial(trial);
      return {
        ...projected,
        ...(trial.error ? { error: trial.error } : { output: trial.reply }),
        metadata: {
          ...projected.metadata,
          model, effort: 'low', workspace: workspace.root, calls: trial.calls, planned,
          toolCalls: trial.toolCalls, providerResponses: trial.providerResponses,
          elapsedMillis: trial.elapsedMillis, usage: trial.usage, kastSha256: await hashBinary(kast),
          taskSha256: totalHash(prompt), systemSha256: trial.systemSha256,
          skillSha256: trial.guidance?.skillSha256 ?? null, guideSha256: trial.guidance?.guideSha256 ?? null,
        },
      };
    } catch (error) {
      return { ...projectPiTrial(error.trial), error: error.message };
    } finally {
      if (workspace?.owned) {
        await child(kast, ['stop', '--workspace', workspace.root, '--json'], { cwd: workspace.owned, timeout: 60_000 });
        await rm(workspace.owned, { recursive: true, force: true });
      }
      await rm(directory, { recursive: true, force: true });
    }
  }
}
