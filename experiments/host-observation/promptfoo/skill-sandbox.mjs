const helpCommands = new Set([
  '--help', 'query --help', 'diagnostic --help', 'diagnostic check --help',
  'result --help', 'result read --help', 'source --help', 'source read --help',
  'describe --help',
]);
const describeOperations = new Set(['query', 'diagnostic.check', 'result.read', 'source.read']);
const discoveryFlags = new Set(['--section', '--pointer', '--jq', '--jq-timeout-ms', '--jq-max-bytes', '--max-entries', '--max-returned-bytes', '--catalog']);
const queryFlags = new Set([
  '--name', '--name-match', '--in', '--kind', '--source-set', '--fields', '--output',
  '--ref', '--relation', '--terminal', '--max-results', '--max-returned-bytes', '--jq',
]);
const sourceFlags = new Set(['--ref', '--region', '--text', '--max-returned-bytes']);
const runtimeKeys = [
  'PATH', 'HOME', 'USER', 'LOGNAME', 'LANG', 'LC_ALL', 'TMPDIR', 'SystemRoot', 'USERPROFILE',
  'JAVA_HOME', 'GRADLE_USER_HOME', 'KAST_GRADLE_IMPORT_VARIABLES', 'KAST_INSTALL_ROOT',
  'KAST_WORKSPACE_DEPENDENCY_SHARING',
  'KAST_STATE_HOME', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME', 'XDG_STATE_HOME', 'XDG_CACHE_HOME',
];

export function runtimeEnvironment(source = process.env) {
  return {
    ...Object.fromEntries(runtimeKeys.filter(key => source[key] !== undefined).map(key => [key, source[key]])),
    KAST_OUTPUT_TOON: '0',
  };
}

export function parseFlags(args, allowed) {
  const values = new Map();
  for (let cursor = 0; cursor < args.length; cursor++) {
    const argument = args[cursor];
    const split = argument.indexOf('=');
    const flag = split < 0 ? argument : argument.slice(0, split);
    if (flag === '--json' && argument === flag) continue;
    if (!allowed.has(flag)) return null;
    const value = split < 0 ? args[++cursor] : argument.slice(split + 1);
    if (value === undefined || value.startsWith('--')) return null;
    const existing = values.get(flag) ?? [];
    values.set(flag, [...existing, value]);
  }
  return values;
}

function scopedDirectory(path) {
  return path !== '' && !path.startsWith('/') && !path.includes('\\') && !path.split('/').includes('..');
}

function permittedRequest(input) {
  if (typeof input !== 'string' || Buffer.byteLength(input) > 8192) return false;
  let document;
  try { document = JSON.parse(input); } catch { return false; }
  const action = document?.request ?? document;
  if (action?.type === 'READ_SOURCE') return typeof action.candidateRef === 'string';
  if (action?.type !== 'RUN' || action.source?.type !== 'SYMBOL_REFS' || action.source.symbolRefs?.length !== 1 ||
      !Array.isArray(action.steps) || action.steps.length < 1 || action.steps.length > 2 ||
      action.terminalStep?.type !== 'EMIT') return false;
  const [step, last] = action.steps;
  return step?.type === 'EXPAND_RELATION' && [undefined, 'DISTINCT_SYMBOLS'].includes(last?.type) &&
    (!step.expansionScope?.directory || scopedDirectory(step.expansionScope.directory));
}

function inertJq(flags) {
  return (flags.get('--jq') ?? []).every(value => !/\b(?:env|input|inputs|include|import|module)\b|\$ENV/i.test(value));
}

export function permittedKast(argv, scope, input) {
  if (argv.some(value => typeof value !== 'string' || value.length > 4096 || value.includes('\0'))) return false;
  if (input !== undefined) {
    return scope === 'repository' && ['query --request -', 'query --request - --json'].includes(argv.join(' ')) &&
      permittedRequest(input);
  }
  if (helpCommands.has(argv.join(' '))) return true;
  const [command, operation, ...rest] = argv;
  if (command === 'describe' && (operation === undefined || describeOperations.has(operation))) {
    const flags = parseFlags(rest, discoveryFlags);
    return flags !== null && inertJq(flags);
  }
  if (scope === 'draft') return false;
  if (command === 'source' && operation === 'read' && scope === 'repository') {
    const flags = parseFlags(rest, sourceFlags);
    return flags !== null && flags.get('--ref')?.length === 1;
  }
  if (command !== 'query') return false;
  const flags = parseFlags(argv.slice(1), queryFlags);
  return flags !== null && (flags.has('--name') || flags.has('--ref')) &&
    (flags.get('--in') ?? []).every(scopedDirectory) && inertJq(flags);
}
