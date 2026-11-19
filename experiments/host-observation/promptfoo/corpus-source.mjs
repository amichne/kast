import { spawnSync } from 'node:child_process';

const sourceBuffer = 16 * 1024 * 1024;
const pathArgumentBytes = 64 * 1024;
const lineCharacters = 900;

export const corpusBaseline = Object.freeze({
  id: 'tracked-source-v2',
  qualification: 'RESTRICTED',
  capabilities: ['pinned-regular-file-list', 'paged-text-read', 'scoped-regex-search'],
  limits: { captureBytes: sourceBuffer, searchRows: 30, maximumReadLines: 200, lineCharacters },
  panelA: { admitted: false, reason: 'ORDINARY_COMPILER_TEST_INSPECTION_UNAVAILABLE' },
});

export function withinWorkspace(path) {
  return typeof path === 'string' && path.length <= 512 && !/[\\\x00-\x1f\x7f]/.test(path) &&
    path.split('/').every(part => !['.', '..', ''].includes(part)) && !path.startsWith('~');
}

function relativePath(path) {
  if (!withinWorkspace(path)) throw new Error('PATH_REJECTED');
  return path;
}

function page(rows, offset, size) {
  integer(offset, 0, 1_000_000);
  const end = offset + size;
  return { rows: rows.slice(offset, end), nextOffset: end < rows.length ? end : null,
    totalRows: rows.length, truncated: end < rows.length };
}

function integer(value, minimum, maximum) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error('PAGE_REJECTED');
}

function admittedSuffix(suffix) {
  if (suffix !== undefined && (typeof suffix !== 'string' || suffix.length > 24 || !/^\.[a-zA-Z0-9.]+$/.test(suffix))) {
    throw new Error('SUFFIX_REJECTED');
  }
  return suffix;
}

function pathBatches(paths) {
  const batches = [];
  let batch = [], bytes = 0;
  for (const path of paths) {
    const size = Buffer.byteLength(path) + 1;
    if (bytes + size > pathArgumentBytes && batch.length) { batches.push(batch); batch = []; bytes = 0; }
    batch.push(path);
    bytes += size;
  }
  if (batch.length) batches.push(batch);
  return batches;
}

export function createCorpusSource({ workspace, revision, git }) {
  function gitOutput(args, emptyMatch = false, maxBuffer = sourceBuffer, timeout = 15_000) {
    const result = spawnSync(git, ['--no-pager', '--literal-pathspecs', ...args], {
      cwd: workspace, encoding: 'utf8', timeout, maxBuffer, killSignal: 'SIGKILL',
      env: { PATH: process.env.PATH, LC_ALL: 'C', GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null', GIT_NO_REPLACE_OBJECTS: '1' },
    });
    if (result.error?.code === 'ENOBUFS') throw new Error('SOURCE_OUTPUT_LIMIT_USE_NARROWER_SCOPE');
    if (result.error?.code === 'ETIMEDOUT') throw new Error('SOURCE_TIME_LIMIT_USE_NARROWER_SCOPE');
    if (emptyMatch && result.status === 1 && !result.error) return '';
    if (result.error || result.status !== 0) throw new Error('SOURCE_REQUEST_FAILED');
    return result.stdout;
  }

  function tree(directory) {
    const args = directory === undefined ? [] : [relativePath(directory)];
    return gitOutput(['ls-tree', '-r', '-z', '--full-tree', revision, '--', ...args]).split('\0').flatMap(entry => {
      const match = /^100(?:644|755) blob ([0-9a-f]{40})\t([^\0]+)$/.exec(entry);
      return match ? [{ blob: match[1], path: match[2] }] : [];
    });
  }

  return {
    hasFile: path => tree(path).some(entry => entry.path === path),
    list({ scope, suffix, offset = 0 }) {
      admittedSuffix(suffix);
      return page(tree(scope).map(file => file.path).filter(path => !suffix || path.endsWith(suffix)), offset, 100);
    },
    read({ path, startLine = 1, lineCount = 100, startColumn = 1 }) {
      integer(startLine, 1, 1_000_000);
      integer(lineCount, 1, 200);
      integer(startColumn, 1, sourceBuffer);
      const file = tree(path).find(entry => entry.path === path);
      if (!file) throw new Error('TRACKED_FILE_UNAVAILABLE');
      const content = gitOutput(['cat-file', 'blob', file.blob]);
      if (content.includes('\0')) throw new Error('BINARY_FILE');
      const lines = content === '' ? [] : content.replace(/\n$/, '').split('\n');
      const { rows, nextOffset } = page(lines, startLine - 1, lineCount);
      const excerpts = rows.map((text, index) => {
        const endColumn = startColumn - 1 + lineCharacters;
        return { line: startLine + index, text: text.slice(startColumn - 1, endColumn), startColumn,
          lineTruncated: text.length > endColumn, nextColumn: text.length > endColumn ? endColumn + 1 : null };
      });
      return { path, rows: excerpts, totalLines: lines.length, nextLine: nextOffset === null ? null : nextOffset + 1,
        truncated: nextOffset !== null || excerpts.some(row => row.lineTruncated) };
    },
    search({ pattern, scope, suffix, offset = 0 }) {
      if (typeof pattern !== 'string' || pattern.length < 1 || pattern.length > 240 || /[\0\r\n]/.test(pattern)) throw new Error('PATTERN_REJECTED');
      admittedSuffix(suffix);
      integer(offset, 0, 1_000_000);
      // Select exact pinned paths first: irrelevant suffixes must never exhaust grep's capture budget.
      const paths = tree(scope).map(file => file.path).filter(path => !suffix || path.endsWith(suffix));
      const rows = [];
      let remainingBytes = sourceBuffer;
      const deadline = performance.now() + 15_000;
      for (const batch of pathBatches(paths)) {
        const remainingMillis = Math.ceil(deadline - performance.now());
        if (remainingMillis <= 0) throw new Error('SOURCE_TIME_LIMIT_USE_NARROWER_SCOPE');
        if (remainingBytes <= 0) throw new Error('SOURCE_OUTPUT_LIMIT_USE_NARROWER_SCOPE');
        const output = gitOutput(['grep', '-z', '-n', '-E', '-I', '--no-column', '--color=never', '--no-textconv',
          '--no-recurse-submodules', '-e', pattern, revision, '--', ...batch], true, remainingBytes, remainingMillis);
        remainingBytes -= Buffer.byteLength(output);
        for (const [record] of output.matchAll(/[^\0]+\0[0-9]+\0[^\n]*\n/g)) {
          const [file, line, entry] = record.split('\0');
          const text = entry.slice(0, -1);
          rows.push({ path: file.slice(revision.length + 1), line: Number(line), text: text.slice(0, lineCharacters),
            lineTruncated: text.length > lineCharacters, nextColumn: text.length > lineCharacters ? lineCharacters + 1 : null });
        }
      }
      return page(rows, offset, 30);
    },
  };
}
