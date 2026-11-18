import { createHash } from 'node:crypto';
import { lstatSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';

const maxFileBytes = 64 * 1024;
const maxListedFiles = 256;
const maximumMatches = 24;
const readable = /\.(?:kt|kts|properties|md)$/;
const ignoredDirectories = new Set(['build', 'out', 'node_modules']);

function components(path) {
  if (typeof path !== 'string' || path.length > 512 || isAbsolute(path) || /[\\\0]/.test(path)) throw Error('PATH_REJECTED');
  const parts = path.split('/');
  if (parts.some(part => !part || part.startsWith('.') || ignoredDirectories.has(part))) throw Error('PATH_REJECTED');
  return parts;
}

function workspaceFile(root, parts) {
  let current = root;
  for (const part of parts.slice(0, -1)) {
    current = join(current, part);
    if (!lstatSync(current).isDirectory()) throw Error('PATH_REJECTED');
  }
  return join(current, parts.at(-1));
}

function kotlinParts(path) {
  const parts = components(path);
  if (parts.slice(0, 3).join('/') !== 'src/main/kotlin' || !path.endsWith('.kt')) throw Error('EDIT_REJECTED');
  return parts;
}

function readCandidate(root, path) {
  if (!readable.test(path)) throw Error('PATH_REJECTED');
  const current = workspaceFile(root, components(path));
  const info = lstatSync(current);
  if (!info.isFile() || info.nlink !== 1 || info.size > maxFileBytes) throw Error('PATH_REJECTED');
  return { path: current, text: readFileSync(current, 'utf8') };
}

export function listWorkspaceFiles(root) {
  const paths = [];
  function visit(directory, parts) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (entry.name.startsWith('.') || ignoredDirectories.has(entry.name)) continue;
      const path = [...parts, entry.name];
      if (entry.isDirectory()) visit(join(directory, entry.name), path);
      else if (entry.isFile() && readable.test(entry.name)) paths.push(path.join('/'));
      if (paths.length > maxListedFiles) throw Error('WORKSPACE_FILE_LIMIT');
    }
  }
  visit(root, []);
  return paths.sort();
}

export function readWorkspaceFile(root, path) {
  return readCandidate(root, path).text;
}

export function searchWorkspace(root, text) {
  if (typeof text !== 'string' || text.length === 0 || text.length > 200 || text.includes('\n')) throw Error('SEARCH_REJECTED');
  const matches = [];
  for (const path of listWorkspaceFiles(root)) {
    const lines = readWorkspaceFile(root, path).split('\n');
    for (const [index, line] of lines.entries()) {
      const position = line.indexOf(text);
      if (position < 0) continue;
      if (matches.length === maximumMatches) return { matches, truncated: true };
      matches.push({ path, line: index + 1, text: line.slice(Math.max(0, position - 120), position + text.length + 120) });
    }
  }
  return { matches, truncated: false };
}

export function replaceWorkspaceText(root, path, before, after) {
  kotlinParts(path);
  if (typeof before !== 'string' || before.length === 0 || typeof after !== 'string') throw Error('EDIT_REJECTED');
  const file = readCandidate(root, path);
  const offset = file.text.indexOf(before);
  if (offset < 0 || file.text.indexOf(before, offset + 1) >= 0) throw Error('EDIT_TARGET_NOT_UNIQUE');
  const updated = file.text.slice(0, offset) + after + file.text.slice(offset + before.length);
  if (Buffer.byteLength(updated) > maxFileBytes) throw Error('WORKSPACE_FILE_LIMIT');
  writeFileSync(file.path, updated);
  return { path, changed: updated !== file.text };
}

export function createWorkspaceFile(root, path, text) {
  const destination = workspaceFile(root, kotlinParts(path));
  if (typeof text !== 'string') throw Error('EDIT_REJECTED');
  if (Buffer.byteLength(text) > maxFileBytes || listWorkspaceFiles(root).length >= maxListedFiles) throw Error('WORKSPACE_FILE_LIMIT');
  try { writeFileSync(destination, text, { mode: 0o600, flag: 'wx' }); }
  catch (error) {
    if (error?.code === 'EEXIST') throw Error('EDIT_TARGET_EXISTS');
    throw error;
  }
  return { path };
}

export function workspaceSourceIdentity(root) {
  const digest = createHash('sha256');
  for (const path of listWorkspaceFiles(root)) {
    const content = readWorkspaceFile(root, path);
    digest.update(`${path.length}:${path}${Buffer.byteLength(content)}:${content}`);
  }
  return digest.digest('hex');
}
