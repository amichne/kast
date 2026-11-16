import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {fromMarkdown} from 'mdast-util-from-markdown';
import {frontmatterFromMarkdown} from 'mdast-util-frontmatter';
import {frontmatter} from 'micromark-extension-frontmatter';

type FailureCode = 'MARKDOWN_INPUT' | 'MARKDOWN_RUNTIME' | 'MARKDOWN_PARSE' | 'MARKDOWN_LIMIT' | 'MARKDOWN_NODE';
type RequestDocument = Readonly<{id: number; source: string; sha256: string}>;
type Request = Readonly<{type: 'PARSE_MARKDOWN'; schemaVersion: 1; documents: ReadonlyArray<RequestDocument>}>;
type SourceRange = Readonly<{kind: 'TEXT' | 'LEGACY_OPAQUE' | 'CODE'; start: number; end: number}>;
type ParsedDocument = Readonly<{id: number; sha256: string; ranges: ReadonlyArray<SourceRange>}>;
type Response = Readonly<{type: 'PARSED_MARKDOWN'; schemaVersion: 1; documents: ReadonlyArray<ParsedDocument>}>;
type Rejection = Readonly<{type: 'PARSER_REJECTED'; schemaVersion: 1; code: FailureCode}>;
type Node = Readonly<{type: string; position?: {start: {offset?: number | undefined}; end: {offset?: number | undefined}} | undefined; children?: ReadonlyArray<Node> | undefined}>;
const MAX_BYTES = 8 * 1024 * 1024;
const MAX_DOCUMENTS = 4096;
const MAX_RANGES = 262144;
const yaml = ['yaml', {type: 'yaml', fence: {open: '---', close: '...'}}] as const;

function reject(code: FailureCode): never {
  const response: Rejection = {type: 'PARSER_REJECTED', schemaVersion: 1, code};
  process.stdout.write(JSON.stringify(response));
  process.exit(2);
}
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function keys(value: Record<string, unknown>, expected: ReadonlyArray<string>): boolean {
  return Object.keys(value).length === expected.length && expected.every(key => Object.hasOwn(value, key));
}
function request(value: unknown): Request {
  if (!record(value) || !keys(value, ['type', 'schemaVersion', 'documents']) || value.type !== 'PARSE_MARKDOWN' || value.schemaVersion !== 1 || !Array.isArray(value.documents)) reject('MARKDOWN_INPUT');
  if (value.documents.length > MAX_DOCUMENTS) reject('MARKDOWN_LIMIT');
  const documents: RequestDocument[] = [];
  const identities = new Set<number>();
  for (const item of value.documents as unknown[]) {
    if (!record(item) || !keys(item, ['id', 'source', 'sha256']) || typeof item.id !== 'number' || !Number.isSafeInteger(item.id) || item.id < 0 || typeof item.source !== 'string' || typeof item.sha256 !== 'string' || identities.has(item.id)) reject('MARKDOWN_INPUT');
    const sha256 = createHash('sha256').update(item.source, 'utf8').digest('hex');
    if (sha256 !== item.sha256) reject('MARKDOWN_INPUT');
    identities.add(item.id);
    documents.push({id: item.id, source: item.source, sha256});
  }
  return {type: 'PARSE_MARKDOWN', schemaVersion: 1, documents};
}
function ranges(root: Node): SourceRange[] {
  const result: SourceRange[] = [];
  function visit(node: Node): void {
    switch (node.type) {
      case 'text':
      case 'html':
      case 'image':
      case 'imageReference':
      case 'definition':
      case 'code':
      case 'inlineCode': {
        const start = node.position?.start.offset;
        const end = node.position?.end.offset;
        if (start === undefined || end === undefined) reject('MARKDOWN_PARSE');
        const kind = node.type === 'text' ? 'TEXT' : node.type === 'code' || node.type === 'inlineCode' ? 'CODE' : 'LEGACY_OPAQUE';
        result.push({kind, start, end});
        if (result.length > MAX_RANGES) reject('MARKDOWN_LIMIT');
        return;
      }
      case 'root': case 'paragraph': case 'heading': case 'blockquote': case 'list': case 'listItem': case 'emphasis': case 'strong': case 'link': case 'linkReference':
        for (const child of node.children ?? []) visit(child);
        return;
      case 'break': case 'thematicBreak': case 'yaml':
        return;
      default:
        reject('MARKDOWN_NODE');
    }
  }
  visit(root);
  return result;
}
if (process.version !== 'v24.21.0') reject('MARKDOWN_RUNTIME');
const input = readFileSync(0);
if (input.length > MAX_BYTES) reject('MARKDOWN_LIMIT');
let decoded: unknown;
try { decoded = JSON.parse(input.toString('utf8')); } catch { reject('MARKDOWN_INPUT'); }
const admitted = request(decoded);
const documents: ParsedDocument[] = [];
try {
  for (const document of admitted.documents) {
    const tree = fromMarkdown(document.source, {extensions: [frontmatter([...yaml])], mdastExtensions: [frontmatterFromMarkdown([...yaml])]});
    documents.push({id: document.id, sha256: document.sha256, ranges: ranges(tree)});
  }
} catch { reject('MARKDOWN_PARSE'); }
const response: Response = {type: 'PARSED_MARKDOWN', schemaVersion: 1, documents};
const output = JSON.stringify(response);
if (Buffer.byteLength(output) > 32 * 1024 * 1024) reject('MARKDOWN_LIMIT');
process.stdout.write(output);
