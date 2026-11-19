import { spawnSync } from 'node:child_process';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { Type } from 'typebox';
import { parseFlags, permittedKast, runtimeEnvironment } from './skill-sandbox.mjs';

const path = Type.String({ minLength: 1, maxLength: 512 });
const scope = Type.Optional(path);
const offset = Type.Optional(Type.Integer({ minimum: 0, maximum: 1_000_000 }));
const suffix = Type.Optional(Type.String({ pattern: '^\\.[a-zA-Z0-9.]+$', maxLength: 24 }));
const sourceBuffer = 16 * 1024 * 1024;
const presentation = ['--output', '--fields', '--max-results', '--max-returned-bytes'];
const queryFlags = new Set([
  ...presentation, '--name', '--ref', '--file', '--offset', '--in', '--kind', '--source-set',
  '--name-match', '--relation', '--terminal', '--max-elapsed-ms',
]);
const resultReadFlags = new Set([...presentation, '--ref', '--cursor', '--evidence-cursor', '--detail']);
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

function withinWorkspace(path: unknown): path is string {
  return typeof path === 'string' && path.length <= 512 && !/[\\\x00-\x1f\x7f]/.test(path) &&
    path.split('/').every(part => !['.', '..', ''].includes(part)) && !path.startsWith('~');
}

function relativePath(path: string) {
  if (!withinWorkspace(path)) throw new Error('PATH_REJECTED');
  return path;
}

function page<T>(rows: T[], offset: number, size: number) {
  const end = offset + size;
  return { rows: rows.slice(offset, end), nextOffset: end < rows.length ? end : null };
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

  function gitOutput(args: string[], emptyMatch = false) {
    const result = spawnSync(git, ['--no-pager', '--literal-pathspecs', ...args], {
      cwd: workspace, encoding: 'utf8', timeout: 15_000, maxBuffer: sourceBuffer, killSignal: 'SIGKILL',
      env: { PATH: process.env.PATH, LC_ALL: 'C', GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null', GIT_NO_REPLACE_OBJECTS: '1' },
    });
    if (result.error?.code === 'ENOBUFS') throw new Error('SOURCE_OUTPUT_LIMIT_USE_NARROWER_SCOPE');
    if (result.error?.code === 'ETIMEDOUT') throw new Error('SOURCE_TIME_LIMIT_USE_NARROWER_SCOPE');
    if (emptyMatch && result.status === 1 && !result.error) return '';
    if (result.error || result.status !== 0) throw new Error('SOURCE_REQUEST_FAILED');
    return result.stdout;
  }

  function sourceRequest(operation: () => unknown) {
    const started = performance.now();
    try { return reply(operation(), { elapsedMillis: Math.round(performance.now() - started) }); }
    catch (error) {
      const reason = error instanceof Error && /^[A-Z_]+$/.test(error.message) ? error.message : 'SOURCE_REQUEST_FAILED';
      return reject(reason, { elapsedMillis: Math.round(performance.now() - started) });
    }
  }

  function tree(directory?: string) {
    const args = directory ? [relativePath(directory)] : [];
    return gitOutput(['ls-tree', '-r', '-z', '--full-tree', revision, '--', ...args]).split('\0').flatMap(entry => {
      const match = /^100(?:644|755) blob ([0-9a-f]{40})\t([^\0]+)$/.exec(entry);
      return match ? [{ blob: match[1], path: match[2] }] : [];
    });
  }

  pi.registerTool({
    name: 'list_files', label: 'List tracked files',
    description: 'List regular files at the pinned commit. Scope is a literal repository-relative directory with no trailing slash. For another page use nextOffset.',
    parameters: Type.Object({ scope, suffix, offset }, { additionalProperties: false }),
    async execute(_id, { scope, suffix, offset = 0 }) {
      return sourceRequest(() => page(tree(scope).map(file => file.path).filter(path => !suffix || path.endsWith(suffix)), offset, 100));
    },
  });
  pi.registerTool({
    name: 'read_file', label: 'Read tracked file',
    description: 'Read up to 30 numbered lines from one tracked text file. For the next page use nextLine as startLine.',
    parameters: Type.Object({ path, startLine: Type.Optional(Type.Integer({ minimum: 1, maximum: 1_000_000 })) }, { additionalProperties: false }),
    async execute(_id, { path, startLine = 1 }) {
      return sourceRequest(() => {
        const file = tree(path).find(entry => entry.path === path);
        if (!file) throw new Error('TRACKED_FILE_UNAVAILABLE');
        const content = gitOutput(['cat-file', 'blob', file.blob]);
        if (content.includes('\0')) throw new Error('BINARY_FILE');
        const lines = content === '' ? [] : content.replace(/\n$/, '').split('\n');
        const { rows, nextOffset } = page(lines, startLine - 1, 30);
        return { path, rows: rows.map((text, index) => ({ line: startLine + index, text: text.slice(0, 900), lineTruncated: text.length > 900 })),
          nextLine: nextOffset === null ? null : nextOffset + 1 };
      });
    },
  });
  pi.registerTool({
    name: 'search_source', label: 'Search tracked text',
    description: 'Use Git extended regex to search tracked text at the pinned commit. Scope is a literal repository-relative file or directory. For another page use nextOffset.',
    parameters: Type.Object({ pattern: Type.String({ minLength: 1, maxLength: 240 }), scope, suffix, offset }, { additionalProperties: false }),
    async execute(_id, { pattern, scope, suffix, offset = 0 }) {
      return sourceRequest(() => {
        if (/[\0\r\n]/.test(pattern)) throw new Error('PATTERN_REJECTED');
        const paths = scope ? [relativePath(scope)] : [];
        const output = gitOutput(['grep', '-z', '-n', '-E', '-I', '--no-column', '--color=never', '--no-textconv',
          '--no-recurse-submodules', '-e', pattern, revision, '--', ...paths], true);
        const rows = Array.from(output.matchAll(/[^\0]+\0[0-9]+\0[^\n]*\n/g), ([record]) => {
          const [file, line, entry] = record.split('\0');
          const text = entry.slice(0, -1);
          return { path: file.slice(revision.length + 1), line: Number(line), text: text.slice(0, 900), lineTruncated: text.length > 900 };
        }).filter(row => !suffix || row.path.endsWith(suffix));
        return page(rows, offset, 30);
      });
    },
  });

  const kast = process.env.KAST_CORPUS_KAST;
  if (!kast) return;
  const issuedResults = new Set<string>();
  const issuedContinuations = new Set<string>();
  const trackedFile = (path: string) => tree(path).some(entry => entry.path === path);

  function admitsKast(argv: string[], stdin?: string) {
    if (argv.some(value => value.includes('\0') || value.includes('\n'))) return false;
    if (stdin !== undefined) {
      return ['query --request -', 'query --request - --json'].includes(argv.join(' ')) &&
        admittedQueryDocument(stdin, trackedFile);
    }
    if (help.has(argv.join(' '))) return true;
    const [command, operation, ...rest] = argv;
    if (command === 'describe') {
      if (!readableOperations.has(operation)) return false;
      const flags = parseFlags(rest, discoveryFlags);
      return flags !== null && (flags.get('--jq') ?? []).every(value =>
        !/\b(?:env|input|inputs|include|import|module)\b|\$ENV/i.test(value));
    }
    if (command === 'source') return permittedKast(argv, 'repository', stdin);
    if (command === 'result') {
      const flags = operation === 'read' ? parseFlags(rest, resultReadFlags)
        : operation === 'resume' ? parseFlags(rest, resultResumeFlags) : null;
      if (!flags) return false;
      const values = flags.get(operation === 'read' ? '--ref' : '--continuation') ?? [];
      const issued = operation === 'read' ? issuedResults : issuedContinuations;
      return values.length === 1 && issued.has(values[0]);
    }
    if (command !== 'query') return false;
    const flags = parseFlags(argv.slice(1).filter(arg => arg !== '--retain'), queryFlags);
    if (!flags || !['--name', '--ref', '--file'].some(flag => flags.has(flag))) return false;
    return (flags.get('--in') ?? []).every(path => path === '.' || withinWorkspace(path)) &&
      (flags.get('--file') ?? []).every(path => withinWorkspace(path) && trackedFile(path));
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
      try {
        if (!admitsKast(argv, stdin)) return reject('KAST_COMMAND_REJECTED');
      } catch {
        return reject('KAST_COMMAND_REJECTED');
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
}
