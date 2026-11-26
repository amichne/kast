import { spawnSync } from 'node:child_process';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { Type } from 'typebox';
import { parseFlags, permittedKast, runtimeEnvironment } from './skill-sandbox.mjs';
import { createCorpusSource, withinWorkspace } from './corpus-source.mjs';

const path = Type.String({ minLength: 1, maxLength: 512 });
const scope = Type.Optional(path);
const offset = Type.Optional(Type.Integer({ minimum: 0, maximum: 1_000_000 }));
const suffix = Type.Optional(Type.String({ pattern: '^\\.[a-zA-Z0-9.]+$', maxLength: 24 }));
const presentation = ['--output', '--fields', '--max-results', '--max-returned-bytes'];
// Every read-only query flag that the shipped skill teaches. Effectful or workspace-moving flags stay excluded.
const resultHandleFlags = ['--from', '--intersect', '--union', '--minus', '--with', '--without'];
const queryFlags = new Set([
  ...presentation, '--name', '--ref', '--file', '--offset', '--in', '--kind', '--source-set',
  '--name-match', '--relation', '--terminal', '--max-elapsed-ms', '--jq', '--jq-timeout-ms', '--jq-max-bytes',
  '--visibility', '--library', '--relation-in', '--relation-source-set', ...resultHandleFlags,
]);
const queryBooleanFlags = new Set(['--retain', '--all', '--relation-production-only', '--relation-exclude-generated']);
const querySources = ['--name', '--ref', '--file', '--library', '--from'];
const jqEscape = /\b(?:env|input|inputs|include|import|module)\b|\$ENV/i;

type Admission = { admitted: true } | { admitted: false; cause: string; flag?: string };
const admitted: Admission = { admitted: true };
const refused = (cause: string, flag?: string): Admission =>
  ({ admitted: false, cause, ...(flag && /^--[a-z][a-z-]{0,39}$/.test(flag) && { flag }) });
const resultReadFlags = new Set([...presentation, '--ref', '--cursor', '--evidence-cursor', '--detail',
  '--jq', '--jq-timeout-ms', '--jq-max-bytes']);
const resultResumeFlags = new Set(['--continuation', '--max-results', '--max-returned-bytes']);
const discoveryFlags = new Set([
  '--section', '--pointer', '--cursor', '--max-results', '--max-returned-bytes', '--jq', '--jq-timeout-ms', '--jq-max-bytes',
]);
const help = new Set(['--help', 'query --help', 'source read --help', 'result read --help', 'result resume --help', 'describe --help']);
const readableOperations = new Set(['query', 'source.read', 'result.read', 'result.resume']);
const querySteps = new Set(['EXPAND_RELATION', 'WALK', 'DISTINCT_SYMBOLS', 'WHERE']);

function reply(content: unknown, details: Record<string, unknown> = {}, isError = false) {
  const text = typeof content === 'string' ? content : JSON.stringify(content);
  return { content: [{ type: 'text' as const, text }], details, isError };
}

function reject(reason: string, details: Record<string, unknown> = {}) {
  return reply({ error: reason }, { reason, ...details }, true);
}

function token(value: unknown) {
  return typeof value === 'string' && /^[A-Za-z_$][A-Za-z0-9_.$-]{0,79}$/.test(value) ? value : null;
}

function count(value: unknown) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function admittedQueryDocument(input: string, trackedFile: (path: string) => boolean) {
  let document;
  try { document = JSON.parse(input); } catch { return false; }
  if (document?.request && Object.keys(document).some(key => !['request', 'verbose'].includes(key))) return false;
  const request = document?.request ?? document;
  if (request?.type === 'READ_SOURCE') return typeof request.candidateRef === 'string';
  if (request?.type !== 'RUN' || request.terminalStep?.type !== 'EMIT') return false;
  const steps = request.steps ?? [];
  if (!Array.isArray(steps) || steps.length > 8 || !steps.every(step => querySteps.has(step?.type) &&
      (step.expansionScope?.directory == null || withinWorkspace(step.expansionScope.directory)))) return false;
  const source = request.source;
  if (source?.type === 'AT_LOCATION') return withinWorkspace(source.file) && trackedFile(source.file);
  if (source?.type === 'SYMBOL_REFS') return Array.isArray(source.symbolRefs) && source.symbolRefs.length > 0;
  return ['SEARCH_DECLARATIONS', 'ALL_DECLARATIONS'].includes(source?.type) &&
    (source.scope?.relativeDirectoryPath == null || source.scope.relativeDirectoryPath === '.' ||
      withinWorkspace(source.scope.relativeDirectoryPath));
}

function semanticReceipt(stdout: string, status: number | null, elapsedMillis: number, workspace: string) {
  let response;
  try { response = JSON.parse(stdout); } catch { return null; }
  const document = response?.document;
  const hostRejected = document?.type === 'HOST_REJECTED' || response?.type === 'HOST_REJECTED';
  const rejection = document?.rejection;
  const evidence = rejection?.detail?.evidence ?? document;
  const question = evidence?.question;
  const relation = Array.isArray(question?.steps) ? token(question.steps.find(step => step?.relation)?.relation) : null;
  const selection = token(question?.from?.match?.text);
  const exhaustive = evidence?.coverage?.exhaustive === true;
  const failureCount = count(evidence?.original_evidence_summary?.failure_count);
  const omissionCount = count(evidence?.original_evidence_summary?.omission_count);
  const rows = Array.isArray(document?.items) ? document.items : [];
  const items = rows.slice(0, 16).flatMap(item => {
    const name = token(item?.name);
    const file = item?.location?.file;
    const path = typeof file === 'string' && file.startsWith(`${workspace}/`) ? file.slice(workspace.length + 1) : file;
    if (!name || !withinWorkspace(path)) return [];
    const startInclusive = count(item.location.range?.startInclusive);
    const endExclusive = count(item.location.range?.endExclusive);
    const range = startInclusive !== null && endExclusive !== null && startInclusive <= endExclusive
      ? { startInclusive, endExclusive } : null;
    return [{ name, path, range }];
  });
  return {
    kind: relation ? 'relation' : selection ? 'selection' : 'query',
    selection, relation, exhaustive, failureCount, omissionCount,
    complete: status === 0 && response.type === 'complete' && document?.status === 'complete' &&
      exhaustive && failureCount === 0 && omissionCount === 0 && document.live?.root === workspace &&
      document.evidence_window?.type === 'FINAL',
    itemCount: Array.isArray(document?.items) ? document.items.length : null,
    items,
    omittedItemCount: rows.length - items.length,
    outcome: token(response?.type),
    failureCategory: hostRejected ? 'HOST_REJECTED' : token(response?.boundary) ?? token(document?.failure?.type),
    hostRejected,
    failureKind: token(document?.failure?.cause?.reason) ?? token(document?.failure) ?? token(rejection?.detail?.cause?.type) ??
      token(rejection?.type) ?? token(response?.failure?.type) ??
      token(response?.failure) ?? token(response?.detail?.cause) ?? token(response?.error?.code) ?? token(response?.error?.type) ??
      (status !== 0 ? token(response?.type) : null),
    elapsedMillis,
  };
}

export default function (pi: ExtensionAPI) {
  const workspace = process.env.KAST_CORPUS_WORKSPACE!;
  const revision = process.env.KAST_CORPUS_REVISION!;
  const git = process.env.KAST_CORPUS_GIT!;
  if (!/^[0-9a-f]{40}$/.test(revision)) throw new Error('REVISION_REJECTED');
  const source = createCorpusSource({ workspace, revision, git });

  function sourceRequest(operation: () => unknown) {
    const started = performance.now();
    try { return reply(operation(), { elapsedMillis: Math.round(performance.now() - started) }); }
    catch (error) {
      const reason = error instanceof Error && /^[A-Z_]+$/.test(error.message) ? error.message : 'SOURCE_REQUEST_FAILED';
      return reject(reason, { elapsedMillis: Math.round(performance.now() - started) });
    }
  }

  pi.registerTool({
    name: 'list_files', label: 'List tracked files',
    description: 'List regular files at the pinned commit. Scope is a literal repository-relative directory with no trailing slash. For another page use nextOffset.',
    parameters: Type.Object({ scope, suffix, offset }, { additionalProperties: false }),
    async execute(_id, { scope, suffix, offset = 0 }) {
      return sourceRequest(() => source.list({ scope, suffix, offset }));
    },
  });
  pi.registerTool({
    name: 'read_file', label: 'Read tracked file',
    description: 'Read numbered lines from one pinned tracked text file (100 by default, at most 200). Use nextLine as startLine. For a long line use its line as startLine, lineCount 1 and nextColumn as startColumn; columns count UTF-16 code units.',
    parameters: Type.Object({ path, startLine: Type.Optional(Type.Integer({ minimum: 1, maximum: 1_000_000 })),
      lineCount: Type.Optional(Type.Integer({ minimum: 1, maximum: 200 })),
      startColumn: Type.Optional(Type.Integer({ minimum: 1, maximum: 16 * 1024 * 1024 })) }, { additionalProperties: false }),
    async execute(_id, { path, startLine, lineCount, startColumn }) {
      return sourceRequest(() => source.read({ path, startLine, lineCount, startColumn }));
    },
  });
  pi.registerTool({
    name: 'search_source', label: 'Search tracked text',
    description: 'Use Git extended regex to search pinned tracked text. Literal scope and suffix select files before capture limits apply. Use nextOffset for another page; recover shortened lines with read_file at the returned line and nextColumn.',
    parameters: Type.Object({ pattern: Type.String({ minLength: 1, maxLength: 240 }), scope, suffix, offset }, { additionalProperties: false }),
    async execute(_id, { pattern, scope, suffix, offset = 0 }) {
      return sourceRequest(() => source.search({ pattern, scope, suffix, offset }));
    },
  });

  const kast = process.env.KAST_CORPUS_KAST;
  if (!kast) return;
  const issuedResults = new Set<string>();
  const issuedContinuations = new Set<string>();
  const trackedFile = (path: string) => source.hasFile(path);

  function unknownFlag(args: string[], allowed: Set<string>) {
    return args.map(arg => arg.split('=')[0]).find(arg => arg.startsWith('--') && arg !== '--json' && !allowed.has(arg));
  }

  function admitsKast(argv: string[], stdin?: string): Admission {
    if (argv.some(value => value.includes('\0') || value.includes('\n'))) return refused('CONTROL_CHARACTER');
    if (stdin !== undefined) {
      return ['query --request -', 'query --request - --json'].includes(argv.join(' ')) &&
        admittedQueryDocument(stdin, trackedFile) ? admitted : refused('REQUEST_DOCUMENT_NOT_ADMITTED');
    }
    if (help.has(argv.join(' '))) return admitted;
    const [command, operation, ...rest] = argv;
    if (command === 'describe') {
      if (!readableOperations.has(operation)) return refused('OPERATION_NOT_ADMITTED');
      const flags = parseFlags(rest, discoveryFlags);
      if (!flags) return refused('FLAG_NOT_ADMITTED', unknownFlag(rest, discoveryFlags));
      return (flags.get('--jq') ?? []).every(value => !jqEscape.test(value)) ? admitted : refused('JQ_NOT_ADMITTED', '--jq');
    }
    if (command === 'source') return permittedKast(argv, 'repository', stdin) ? admitted : refused('SOURCE_READ_NOT_ADMITTED');
    if (command === 'result') {
      const allowed = operation === 'read' ? resultReadFlags : operation === 'resume' ? resultResumeFlags : null;
      if (!allowed) return refused('OPERATION_NOT_ADMITTED');
      const flags = parseFlags(rest, allowed);
      if (!flags) return refused('FLAG_NOT_ADMITTED', unknownFlag(rest, allowed));
      const values = flags.get(operation === 'read' ? '--ref' : '--continuation') ?? [];
      const issued = operation === 'read' ? issuedResults : issuedContinuations;
      if (values.length !== 1 || !issued.has(values[0])) return refused('HANDLE_NOT_ISSUED');
      return (flags.get('--jq') ?? []).every(value => !jqEscape.test(value)) ? admitted : refused('JQ_NOT_ADMITTED', '--jq');
    }
    if (command !== 'query') return refused('COMMAND_NOT_ADMITTED');
    const args = argv.slice(1).filter(arg => !queryBooleanFlags.has(arg));
    const flags = parseFlags(args, queryFlags);
    if (!flags) return refused('FLAG_NOT_ADMITTED', unknownFlag(args, queryFlags));
    if (!argv.includes('--all') && !querySources.some(flag => flags.has(flag))) return refused('SOURCE_MISSING');
    const paths = [...(flags.get('--in') ?? []), ...(flags.get('--relation-in') ?? [])];
    if (!paths.every(path => path === '.' || withinWorkspace(path))) return refused('PATH_OUTSIDE_WORKSPACE');
    if (!(flags.get('--file') ?? []).every(path => withinWorkspace(path) && trackedFile(path)))
      return refused('PATH_NOT_TRACKED', '--file');
    const handle = resultHandleFlags.find(flag => (flags.get(flag) ?? []).some(value => !issuedResults.has(value)));
    if (handle) return refused('HANDLE_NOT_ISSUED', handle);
    return (flags.get('--jq') ?? []).every(value => !jqEscape.test(value)) ? admitted : refused('JQ_NOT_ADMITTED', '--jq');
  }

  function rememberIssued(stdout: string) {
    let response;
    try { response = JSON.parse(stdout); } catch { return; }
    const document = response?.document ?? response;
    const recovery = document?.semantic?.recovery;
    for (const value of [document?.retention?.reference, recovery?.result, document?.nextQuery?.request?.result]) {
      if (typeof value === 'string' && value.length <= 4096) issuedResults.add(value);
    }
    for (const value of [document?.progress?.checkpoint?.token, recovery?.continuation]) {
      if (typeof value === 'string' && value.length <= 4096) issuedContinuations.add(value);
    }
  }

  pi.registerTool({
    name: 'kast', label: 'Kast semantic query',
    description: 'Run read-only Kast commands with argv after kast. For a supported query request document use stdin with ["query", "--request", "-"].',
    parameters: Type.Object({ argv: Type.Array(Type.String({ maxLength: 4096 }), { minItems: 1, maxItems: 32 }),
      stdin: Type.Optional(Type.String({ maxLength: 8192 })) }, { additionalProperties: false }),
    async execute(_id, { argv, stdin }) {
      let admission: Admission;
      try {
        admission = admitsKast(argv, stdin);
      } catch {
        admission = refused('ADMISSION_FAILED');
      }
      if (!admission.admitted) {
        const { cause, flag } = admission;
        return reply({ error: 'KAST_COMMAND_REJECTED', cause, ...(flag && { flag }) },
          { reason: 'KAST_COMMAND_REJECTED', cause, ...(flag && { flag }) }, true);
      }
      const started = performance.now();
      const env = {
        ...runtimeEnvironment(),
        ...(process.env.KAST_GRADLE_JAVA_HOME && { KAST_GRADLE_JAVA_HOME: process.env.KAST_GRADLE_JAVA_HOME }),
      };
      const result = spawnSync(kast, argv, { cwd: workspace, env, encoding: 'utf8',
        timeout: 75_000, maxBuffer: 2 * 1024 * 1024, killSignal: 'SIGKILL', input: stdin });
      const elapsedMillis = Math.round(performance.now() - started);
      if (result.error?.code === 'ETIMEDOUT') return reject('KAST_TIME_LIMIT', { elapsedMillis });
      if (result.error?.code === 'ENOBUFS') return reject('KAST_OUTPUT_LIMIT', { elapsedMillis });
      if (result.error || result.status === null) return reject('KAST_EFFECT_UNAVAILABLE', { elapsedMillis });
      const query = ['query', 'result'].includes(argv[0]) && !argv.includes('--help');
      if (query) rememberIssued(result.stdout);
      const receipt = query ? semanticReceipt(result.stdout, result.status, elapsedMillis, workspace) : null;
      const reason = result.status !== 0 && !receipt?.failureKind ? 'KAST_COMMAND_FAILED' : undefined;
      const text = (result.stdout || result.stderr || 'Kast returned no output.').replaceAll(`${workspace}/`, '');
      return reply(text, { status: result.status, elapsedMillis, receipt, ...(reason && { reason }) }, result.status !== 0);
    },
  });

  const recipe = process.env.KAST_CORPUS_RECIPE;
  if (!recipe) return;
  pi.registerTool({
    name: 'kast_recipe', label: 'Kast recipe command',
    description: 'Run the Kast skill\'s scripts/kast-recipe command with argv after kast-recipe: verdict QUERY_FLAGS..., call-sites --ref REF [scope flags], or impact --ref REF [--seed N] [--flow FLOW].',
    parameters: Type.Object({ argv: Type.Array(Type.String({ maxLength: 4096 }), { minItems: 1, maxItems: 32 }) },
      { additionalProperties: false }),
    async execute(_id, { argv }) {
      let admission: Admission;
      try {
        admission = admitsRecipe(argv);
      } catch {
        admission = refused('ADMISSION_FAILED');
      }
      if (!admission.admitted) {
        const { cause, flag } = admission;
        return reply({ error: 'KAST_COMMAND_REJECTED', cause, ...(flag && { flag }) },
          { reason: 'KAST_COMMAND_REJECTED', cause, ...(flag && { flag }) }, true);
      }
      const started = performance.now();
      const env = {
        ...runtimeEnvironment(), KAST_RECIPE_KAST: kast,
        ...(process.env.KAST_GRADLE_JAVA_HOME && { KAST_GRADLE_JAVA_HOME: process.env.KAST_GRADLE_JAVA_HOME }),
      };
      const result = spawnSync('/bin/sh', [recipe, ...argv], { cwd: workspace, env, encoding: 'utf8',
        timeout: 150_000, maxBuffer: 4 * 1024 * 1024, killSignal: 'SIGKILL' });
      const elapsedMillis = Math.round(performance.now() - started);
      if (result.error?.code === 'ETIMEDOUT') return reject('KAST_TIME_LIMIT', { elapsedMillis });
      if (result.error?.code === 'ENOBUFS') return reject('KAST_OUTPUT_LIMIT', { elapsedMillis });
      if (result.error || result.status === null) return reject('KAST_EFFECT_UNAVAILABLE', { elapsedMillis });
      rememberIssuedRecipe(result.stdout);
      const receipt = recipeReceipt(argv[0], result.stdout, result.status, elapsedMillis);
      const text = (result.stdout || result.stderr || 'Kast returned no output.').replaceAll(`${workspace}/`, '');
      return reply(text, { status: result.status, elapsedMillis, receipt }, result.status !== 0);
    },
  });

  /** The recipe forwards verdict flags to `kast query`; the same read-only admission applies to them. */
  function admitsRecipe(argv: string[]): Admission {
    if (argv.some(value => value.includes('\0') || value.includes('\n'))) return refused('CONTROL_CHARACTER');
    const [command, ...rest] = argv;
    if (command === 'verdict') {
      if (rest[0] === '--request') return refused('REQUEST_DOCUMENT_NOT_ADMITTED');
      return admitsKast(['query', ...rest]);
    }
    if (command === 'call-sites') {
      const scope = new Set(['--ref', '--relation-in', '--relation-source-set']);
      const flags = parseFlags(rest.filter(arg => !queryBooleanFlags.has(arg)), scope);
      if (!flags) return refused('FLAG_NOT_ADMITTED', unknownFlag(rest, new Set([...scope, ...queryBooleanFlags])));
      if ((flags.get('--ref') ?? []).length !== 1) return refused('SOURCE_MISSING');
      return (flags.get('--relation-in') ?? []).every(path => path === '.' || withinWorkspace(path))
        ? admitted : refused('PATH_OUTSIDE_WORKSPACE');
    }
    if (command === 'impact') {
      const flags = parseFlags(rest, new Set(['--ref', '--seed', '--flow']));
      if (!flags) return refused('FLAG_NOT_ADMITTED', unknownFlag(rest, new Set(['--ref', '--seed', '--flow'])));
      return (flags.get('--ref') ?? []).length === 1 ? admitted : refused('SOURCE_MISSING');
    }
    return refused('COMMAND_NOT_ADMITTED');
  }

  function rememberIssuedRecipe(stdout: string) {
    try {
      const value = JSON.parse(stdout)?.result;
      if (typeof value === 'string' && value.length <= 4096) issuedResults.add(value);
    } catch {
      // Unparsed recipe output issues no result.
    }
  }

  /** Complete only for a complete, exhaustive verdict with no failures, omissions or deferred evidence. */
  function recipeReceipt(command: string, stdout: string, status: number, elapsedMillis: number) {
    let document;
    try { document = JSON.parse(stdout); } catch { return { kind: 'recipe', command, complete: false, status, elapsedMillis }; }
    const verdict = document?.verdict;
    const complete = verdict ? verdict.type === 'complete' && verdict.exhaustive === true && verdict.failures === 0 &&
      verdict.omissions === 0 && verdict.evidence_complete === true : document?.type === 'complete';
    return { kind: 'recipe', command, complete, status, elapsedMillis,
      ...(verdict && { excludedCallbackCalls: count(verdict.excluded_callback_calls) }) };
  }
}
