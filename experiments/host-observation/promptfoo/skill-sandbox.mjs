import { readdir } from 'node:fs/promises';
import { join, relative } from 'node:path';

// Admission and participant source reads must see the same fixture files after a Gradle import.
export async function fixturePaths(directory, includes, label) {
  const paths = [];
  const directories = [directory];
  while (directories.length) {
    const parent = directories.pop();
    for (const entry of await readdir(parent, { withFileTypes: true })) {
      const path = join(parent, entry.name);
      if (entry.isDirectory() && entry.name === '.gradle') continue;
      if (includes(entry.name)) {
        if (!entry.isFile()) throw Error(`Fixture ${label} paths must be regular files`);
        paths.push(relative(directory, path));
      } else if (entry.isDirectory()) {
        directories.push(path);
      }
    }
  }
  return paths.sort();
}

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
export const sourceFlags = new Set(['--ref', '--region', '--text', '--max-returned-bytes', '--jq']);
const requestFlags = new Set(['--request', '--jq']);
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
  const programs = flags.get('--jq') ?? [];
  return programs.length <= 1 && programs.every(value => !/\b(?:env|input|inputs|include|import|module)\b|\$ENV/i.test(value));
}

export function permittedKast(argv, scope, input) {
  if (argv.some(value => typeof value !== 'string' || value.length > 4096 || value.includes('\0'))) return false;
  if (input !== undefined) {
    const flags = parseFlags(argv.slice(1), requestFlags);
    const requests = flags?.get('--request');
    return scope === 'repository' && argv[0] === 'query' && requests?.length === 1 && requests[0] === '-' &&
      inertJq(flags) && permittedRequest(input);
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
    return flags !== null && flags.get('--ref')?.length === 1 && inertJq(flags);
  }
  if (command !== 'query') return false;
  const flags = parseFlags(argv.slice(1), queryFlags);
  return flags !== null && (flags.has('--name') || flags.has('--ref')) &&
    (flags.get('--in') ?? []).every(scopedDirectory) && inertJq(flags);
}

function toolResult(argv, result, presentation = result.stdout, evidence = result.stdout) {
  return {
    content: [{ type: 'text', text: presentation + result.stderr }],
    details: { argv, status: result.status, stdout: evidence },
    isError: result.status !== 0,
  };
}

export function runSkillKast(argv, input, run) {
  const flag = argv.findIndex(value => value === '--jq' || value.startsWith('--jq='));
  const recordsEvidence = argv[0] === 'query' || (argv[0] === 'source' && argv[1] === 'read');
  if (!recordsEvidence || flag < 0) return toolResult(argv, run(argv, input));
  const separate = argv[flag] === '--jq';
  const program = separate ? argv[flag + 1] : argv[flag].slice('--jq='.length);

  // The planner compiles jq before it rejects the empty request. This checks the participant's
  // expression in isolation, so it cannot escape the parentheses and replace the evidence.
  const admission = run(['query', 'plan', '--request', '-', '--json', '--jq', program], '{}');
  const rejection = JSON.parse(admission.stdout);
  if (admission.status !== 2 || rejection.type !== 'rejected' || rejection.failure !== 'INVALID_ARGUMENTS') {
    if (admission.status !== 0) return toolResult(argv, admission);
    throw Error('Kast did not admit the standalone trial projection');
  }
  const wrapped = '{evidence: tojson, presentation: ((\n' + program + '\n) | tojson)}';
  const invocation = argv.slice();
  invocation.splice(flag, separate ? 2 : 1, '--jq', wrapped);
  const result = run(invocation, input);
  if (result.status !== 0) return toolResult(argv, result);
  const { evidence, presentation } = JSON.parse(result.stdout);
  if (typeof evidence !== 'string' || typeof presentation !== 'string') throw Error('Invalid Kast projection receipt');
  return toolResult(argv, result, presentation + '\n', evidence);
}
