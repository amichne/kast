#!/usr/bin/env node
// Actual installed Pi rendering of captured catalogs. No sessions, tool execution, or local ref expansion.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { findPackageJSON } from 'node:module';
import { pathToFileURL } from 'node:url';

export async function installedPiRendering(sdkRoot) {
  const dependency = name => path.dirname(findPackageJSON(name, pathToFileURL(path.join(sdkRoot, 'package.json'))));
  const coding = relative => import(pathToFileURL(path.join(sdkRoot, relative)));
  const tools = await coding('dist/extensions/mcp/tools.js');
  const codemode = await coding('dist/extensions/codemode/tool.js');
  const extension = await coding('dist/extensions/codemode/index.js');
  const search = await coding('dist/extensions/tool-search/tool.js');
  const resources = await coding('dist/extensions/mcp/resources.js');
  const rendererPath = path.join(dependency('@earendil-works/pi-codemode'), 'dist/declarations.js');
  const converterPath = path.join(dependency('@earendil-works/pi-ai'), 'dist/api/openai-responses-shared.js');
  return { ...tools, ...codemode, ...extension, ...search, ...resources,
    ...await import(pathToFileURL(rendererPath)), ...await import(pathToFileURL(converterPath)),
    rendererPath, converterPath };
}

export function declarationOnlyMcpTool(pi, tool) {
  return pi.createMcpToolDefinition({ server: 'kast', tool,
    name: pi.createMcpToolName('kast', tool.name), exposure: 'codemode',
    namespace: { name: 'mcp__kast', aliases: ['kast'] }, timeoutMs: 60_000,
    getClient: async () => { throw Error('Declaration-only capture forbids tool execution'); } });
}

const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const measure = value => ({ unicodeCharacters: Array.from(value).length,
  utf16CodeUnits: value.length, utf8Bytes: Buffer.byteLength(value, 'utf8'), sha256: sha(value) });

async function capture(sdkRoot, catalogRoot, destination) {
  if (![sdkRoot, catalogRoot, destination].every(value => path.isAbsolute(value ?? '')))
    throw Error('Absolute SDK, catalog-root, and destination paths are necessary');
  const pi = await installedPiRendering(sdkRoot);
  fs.mkdirSync(destination, { recursive: true });
  const write = (relative, text) => { fs.writeFileSync(path.join(destination, relative), text); return measure(text); };
  const arms = [];
  for (const arm of ['baseline', 'candidate-common', 'candidate-complete']) {
    fs.mkdirSync(path.join(destination, arm), { recursive: true });
    const input = path.join(catalogRoot, arm, 'tools-list.json.gz');
    const compressed = fs.readFileSync(input), source = zlib.gunzipSync(compressed).toString('utf8');
    const response = source.trim().split('\n').map(line => JSON.parse(line)).find(value => Array.isArray(value.result?.tools));
    if (!response || response.result.nextCursor) throw Error('Complete tools/list capture is necessary');
    const tools = response.result.tools.map(tool => declarationOnlyMcpTool(pi, tool));
    const declarations = tools.map(tool => pi.toCodemodeDeclaration(tool));
    const samples = declarations.map(tool => pi.renderToolSample(tool));
    const entries = declarations.map((tool, index) => ({ name: tool.name,
      declaration: write(`${arm}/${tool.name}.json`, JSON.stringify(tool)),
      sample: write(`${arm}/${tool.name}.sample.txt`, samples[index]),
      signature: write(`${arm}/${tool.name}.signature.txt`, pi.renderToolSignature(tool)),
      inputType: write(`${arm}/${tool.name}.input.type.txt`, pi.schemaToType(tool.inputSchema, { maxChars: pi.DEFAULT_INPUT_SCHEMA_MAX_CHARS })),
    }));
    const namespaces = new Map(tools.map(tool => [tool.name, tool.namespace]));
    const deferred = new Set(tools.map(tool => tool.name));
    const helpers = [pi.createCodemodeToolDefinition({ models: false }), pi.createToolSearchToolDefinition(),
      ...pi.createMcpResourceToolDefinitions({ exposure: 'codemode', servers: () => [] })];
    const options = { strict: false, supportsStrictMode: true, supportsOpenAIGrammarTools: false };
    const raw = pi.convertResponsesTools(response.result.tools.map(tool => ({ name: tool.name,
      description: tool.description, parameters: tool.inputSchema })), options);
    const registered = pi.convertResponsesTools(tools, options);
    arms.push({ arm, source: input, compressedSha256: sha(compressed), offeredToolCount: tools.length, entries,
      allSamples: write(`${arm}/all-samples.txt`, samples.join('\n')),
      allDeclarations: write(`${arm}/all-declarations.d.ts`, pi.renderDeclarations({ tools: declarations })),
      deferredDescription: write(`${arm}/deferred-description.txt`, pi.createCodemodeDescription(tools,
        { models: false, namespaces, deferred, inlineBudget: pi.DEFAULT_CODEMODE_INLINE_BUDGET })),
      fullDescriptionDiagnostic: write(`${arm}/full-description-diagnostic.txt`, pi.createCodemodeDescription(tools,
        { models: false, namespaces })),
      optionalHelpers: helpers.map(tool => ({ name: tool.name,
        sample: write(`${arm}/optional-helper-${tool.name}.txt`, pi.renderToolSample(pi.toCodemodeDeclaration(tool))) })),
      rawNameResponses: write(`${arm}/raw-name-responses.json`, JSON.stringify(raw)),
      registeredNameResponses: write(`${arm}/registered-name-responses.json`, JSON.stringify(registered)),
    });
  }
  const sourceFiles = [pi.rendererPath, pi.converterPath,
    ...['dist/extensions/mcp/tools.js', 'dist/extensions/codemode/tool.js', 'dist/extensions/codemode/index.js'].map(relative => path.join(sdkRoot, relative))];
  const report = { qualification: 'ACTUAL_INSTALLED_PI_MCP_FACTORY_AND_CODEMODE_RENDERER_NOT_TASK_REPLAY',
    piVersion: JSON.parse(fs.readFileSync(path.join(sdkRoot, 'package.json'))).version,
    sdkRoot, sourceFiles: sourceFiles.map(file => ({ path: file, sha256: sha(fs.readFileSync(file)) })),
    options: { server: 'kast', exposure: 'codemode', models: false, inlineBudget: pi.DEFAULT_CODEMODE_INLINE_BUDGET,
      inputMaxChars: pi.DEFAULT_INPUT_SCHEMA_MAX_CHARS, sampleSeparator: 'single LF, no trailing LF',
      namespaceDescription: 'NOT_IN_CAPTURE', namespaceInstructions: 'NOT_IN_CAPTURE' },
    limits: ['Full description is an explicit diagnostic, not actual task exposure.',
      'Optional helpers are measured separately, not asserted loaded. Resource helpers need resource-capable servers.',
      'Task-loaded declarations, repeated loads, model tokens, and full task consumption are NOT MEASURED.',
      'No session, sandbox, tool, provider, native query, installation, or IDE action ran.'], arms };
  write('measurements.json', JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report.arms.map(({ arm, offeredToolCount, allSamples, registeredNameResponses }) =>
    ({ arm, offeredToolCount, allSamples, registeredNameResponses }))));
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url)
  await capture(...process.argv.slice(2));
