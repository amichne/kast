#!/usr/bin/env node
// Native Pi resource/catalog loading only; never submits a provider request.
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const root = fs.realpathSync(process.env.PI_EVAL_ROOT ?? '');
if (!fs.existsSync(path.join(root, '.pi-eval-owned'))) throw Error('Owned runtime required');
const sdkRoot = path.join(root, 'npm/node_modules/@earendil-works/pi-coding-agent');
const { DefaultResourceLoader, ModelRegistry, ModelRuntime, SettingsManager } = await import(pathToFileURL(path.join(sdkRoot, 'dist/index.js')));
const workspace = fs.mkdtempSync(path.join(root, 'source-only-smoke-'));
fs.writeFileSync(path.join(workspace, 'fixture.kt'), 'package fixture\nclass Smoke\n');
const agentDir = path.join(root, 'agent');
const adapterIndex = process.argv.indexOf('--kast-adapter');
const adapter = adapterIndex >= 0 ? fs.realpathSync(process.argv[adapterIndex + 1]) : null;
const loader = new DefaultResourceLoader({ cwd: workspace, agentDir,
  settingsManager: SettingsManager.inMemory({ packages: [] }),
  additionalExtensionPaths: [path.join(root, 'slopsentral'), ...(adapter ? [adapter] : [])],
  noExtensions: true, noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true });
await loader.reload();
const errors = loader.getExtensions().errors;
const runtime = await ModelRuntime.create({ authPath: path.join(agentDir, 'auth.json'),
  modelsPath: null, modelsStorePath: path.join(root, 'models-cache.json'),
  allowModelNetwork: process.argv.includes('--refresh-catalog'), modelRefreshTimeoutMs: 20000,
  refreshOnCreate: process.argv.includes('--refresh-catalog') });
const registry = new ModelRegistry(runtime);
const model = registry.find('openai-codex', 'gpt-6.1-sol');
const report = { mode: 'NO_INFERENCE', piVersion: JSON.parse(fs.readFileSync(path.join(sdkRoot, 'package.json'))).version,
  child: { node: process.execPath, cwd: workspace, agentDir },
  slopsentralExtensionErrors: errors.map(error => ({ path: error.path, error: String(error.error) })),
  extensions: loader.getExtensions().extensions.map(extension => ({ path: extension.path,
    toolNames: [...extension.tools.keys()] })),
  kastAdapter: adapter, kastNativeQuery: 'NOT_RUN',
  catalogModels: registry.getAll().length,
  requestedModel: { provider: 'openai-codex', id: 'gpt-6.1-sol', discovered: Boolean(model),
    configuredAuth: model ? registry.hasConfiguredAuth(model) : false },
  qualification: 'Catalog and extension loading do not prove native Kast query readiness or model access' };
console.log(JSON.stringify(report, null, 2));
if (errors.length) process.exitCode = 1;
