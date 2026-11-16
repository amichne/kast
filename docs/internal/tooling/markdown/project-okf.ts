import {readFileSync} from 'node:fs';
import {posix} from 'node:path';
import {fromMarkdown} from 'mdast-util-from-markdown';

type Input = Readonly<{path: string; body: string}>;
type Request = Readonly<{documents: ReadonlyArray<Input>; routes: Readonly<Record<string, string>>}>;
type Node = Readonly<{type: string; url?: string | undefined; depth?: number | undefined; position?: {start: {offset?: number | undefined}; end: {offset?: number | undefined}} | undefined; children?: ReadonlyArray<Node> | undefined}>;
type Edit = Readonly<{start: number; end: number; value: string}>;

function reject(): never {
  process.stdout.write(JSON.stringify({type: 'REJECTED', code: 'OKF_MARKDOWN'}));
  process.exit(2);
}
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function request(value: unknown): Request {
  if (!record(value) || Object.keys(value).length !== 2 || !Array.isArray(value.documents) || !record(value.routes)) reject();
  const routes: Record<string, string> = {};
  for (const [key, route] of Object.entries(value.routes)) {
    if (typeof route !== 'string') reject();
    routes[key] = route;
  }
  const documents: Input[] = [];
  for (const item of value.documents as unknown[]) {
    if (!record(item) || Object.keys(item).length !== 2 || typeof item.path !== 'string' || typeof item.body !== 'string') reject();
    documents.push({path: item.path, body: item.body});
  }
  return {documents, routes};
}
function conceptAnchor(path: string): string {
  return `okf-${path.replace(/^openwiki\//u, '').replace(/\.md$/u, '').replaceAll('/', '-')}`;
}
function headingAnchor(text: string): string {
  return text.toLowerCase().replaceAll('`', '').replace(/[^\p{L}\p{N}_ -]/gu, '').replaceAll(' ', '-');
}
function project(input: Input, routes: Readonly<Record<string, string>>): string {
  const source = input.body;
  const tree: Node = fromMarkdown(source);
  const edits: Edit[] = [];
  function url(value: string): string {
    if (/^[a-z][a-z0-9+.-]*:/iu.test(value) || value.startsWith('//')) return value;
    if (value.startsWith('#')) return `#${conceptAnchor(input.path)}-${value.slice(1)}`;
    const hash = value.indexOf('#');
    const path = hash < 0 ? value : value.slice(0, hash);
    const fragment = hash < 0 ? '' : value.slice(hash);
    const resolved = posix.normalize(posix.join(posix.dirname(input.path), path));
    if (resolved.startsWith('../') || posix.isAbsolute(resolved)) reject();
    const route = routes[resolved];
    return route === undefined
      ? `https://github.com/amichne/kast/blob/main/${resolved}${fragment}`
      : `/${route}#${conceptAnchor(resolved)}${fragment ? `-${fragment.slice(1)}` : ''}`;
  }
  function visit(node: Node): void {
    const start = node.position?.start.offset;
    const end = node.position?.end.offset;
    if (node.type === 'link' || node.type === 'image' || node.type === 'definition') {
      if (start === undefined || end === undefined || node.url === undefined) reject();
      const raw = source.slice(start, end);
      const destination = node.type === 'definition' ? raw.indexOf(':') + 1 : raw.lastIndexOf('](') + 2;
      // Preserve literal source. Unrecognized escaped destinations reject instead of a lossy rewrite.
      const offset = raw.indexOf(node.url, destination);
      if (destination < 1 || offset < destination) reject();
      edits.push({start: start + offset, end: start + offset + node.url.length, value: url(node.url)});
    }
    if (node.type === 'heading') {
      if (start === undefined || end === undefined) reject();
      const raw = source.slice(start, end);
      if (!/^#{1,6}\s/u.test(raw)) reject();
      const heading = raw.replace(/^#+\s*/u, '').replace(/\s+#+\s*$/u, '');
      const anchor = node.depth === 1 ? conceptAnchor(input.path) : `${conceptAnchor(input.path)}-${headingAnchor(heading)}`;
      edits.push({start, end: start, value: `<a id="${anchor}" />\n\n`});
      if (node.depth === 1) edits.push({start, end: start + 1, value: '##'});
    }
    for (const child of node.children ?? []) visit(child);
  }
  visit(tree);
  let output = source;
  let limit = source.length + 1;
  for (const edit of edits.sort((left, right) => right.start - left.start || right.end - left.end)) {
    if (edit.end > limit) reject();
    output = output.slice(0, edit.start) + edit.value + output.slice(edit.end);
    limit = edit.start;
  }
  return output;
}
if (process.version !== 'v24.21.0') reject();
const bytes = readFileSync(0);
if (bytes.length > 8 * 1024 * 1024) reject();
let value: unknown;
try { value = JSON.parse(bytes.toString('utf8')); } catch { reject(); }
const input = request(value);
process.stdout.write(JSON.stringify({type: 'PROJECTED', documents: input.documents.map(document => ({path: document.path, body: project(document, input.routes)}))}));
