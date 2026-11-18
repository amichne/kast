import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';

function runCampaign(root, plan, output, options = []) {
  return spawnSync(
    'npm', ['run', 'eval', '--', '-c', 'semantic-campaign.config.mjs', '-o', output, '--no-table', ...options],
    {
      cwd: new URL('./', import.meta.url),
      env: {
        ...process.env,
        KAST_CAMPAIGN_PLAN: plan,
        PROMPTFOO_CONFIG_DIR: join(root, 'state'),
        PROMPTFOO_DISABLE_TELEMETRY: '1',
      },
      encoding: 'utf8',
      timeout: 30_000,
    },
  );
}

test('native semantic scenario preserves blocked variants and the final source witness', () => {
  const root = mkdtempSync(join(tmpdir(), 'kast-semantic-promptfoo-'));
  try {
    const plan = join(root, 'plan.json');
    const evidence = join(root, 'evidence');
    const output = join(root, 'results.json');
    writeFileSync(plan, JSON.stringify({
      repository: 'kast',
      fixture: join(root, 'absent'),
      cli: process.execPath,
      idea_log: join(root, 'absent.log'),
      output: evidence,
      tranche: 'sources',
      case_prefixes: ['B04-text-absent-word'],
    }));
    const execution = runCampaign(root, plan, output);
    assert.equal(execution.error, undefined);
    assert.equal(execution.status, 100, execution.stdout + execution.stderr);
    const rows = JSON.parse(readFileSync(output, 'utf8')).results.results;
    assert.deepEqual(rows.map((row) => row.vars.caseId), [
      'B02-source-anchored-selection',
      'B05-retain-exact-seed',
      'B04-text-absent-word',
      'final-source-witness',
    ]);
    for (const row of rows) {
      assert.equal(row.success, false);
      assert.equal(row.response.error, 'INPUT_UNAVAILABLE');
    }
    const manifest = JSON.parse(readFileSync(join(evidence, 'campaign.json'), 'utf8'));
    assert.deepEqual(manifest.sourceAtEnd, { status: 'BLOCKED', reason: 'INPUT_UNAVAILABLE' });
    assert.deepEqual(
      manifest.variants.find((row) => row.id === 'B04-text-declaration-word').outcome,
      { status: 'NOT_RUN', reason: 'NOT_SELECTED' },
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('end-of-run source drift rejects a filtered campaign after its selected row passed', () => {
  const root = mkdtempSync(join(tmpdir(), 'kast-semantic-promptfoo-'));
  try {
    const relative = 'kernel/src/main/kotlin/io/github/amichne/kast/kernel/Validation.kt';
    const fixture = join(root, 'fixture');
    const source = join(fixture, relative);
    mkdirSync(dirname(source), { recursive: true });
    copyFileSync(new URL(`../../../${relative}`, import.meta.url), source);

    const cli = join(root, 'scripted-mcp');
    writeFileSync(cli, `#!/usr/bin/env node
const { createInterface } = require('node:readline');
createInterface({ input: process.stdin }).on('line', (line) => {
  const request = JSON.parse(line);
  let result;
  if (request.method === 'initialize') {
    result = { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } };
  } else if (request.method === 'tools/list') {
    result = { tools: [] };
  } else {
    if (request.method !== 'tools/call' || request.params.arguments.request.source.declarationName !== 'rejected') {
      throw new Error('Unexpected fixture request');
    }
    result = { structuredContent: {
      status: 'complete', coverage: { exhaustive: true },
      items: [{ name: 'rejected', ref: 'exact:fixture',
        location: { file: ${JSON.stringify(realpathSync(source))}, range: { startInclusive: 0, endExclusive: 65536 } } }],
    }};
  }
  console.log(JSON.stringify({ jsonrpc: '2.0', id: request.id, result }));
});
`, { mode: 0o700 });
    const hook = join(root, 'drift.mjs');
    writeFileSync(hook, String.raw`
import { appendFileSync } from 'node:fs';
export function afterEach({ result }) {
  if (result.success !== true) throw new Error('Scripted variant did not pass');
  appendFileSync(${JSON.stringify(source)}, '\n// source changed after grading\n');
}
`);
    const plan = join(root, 'plan.json');
    const evidence = join(root, 'evidence');
    writeFileSync(plan, JSON.stringify({
      repository: 'kast',
      fixture,
      cli,
      idea_log: join(root, 'absent.log'),
      output: evidence,
      tranche: 'sources',
    }));
    const execution = runCampaign(root, plan, join(root, 'results.json'), [
      '--filter-pattern', '^B02-source-anchored-selection$', '-x', `file://${hook}:afterEach`,
    ]);

    assert.equal(execution.error, undefined);
    const manifest = JSON.parse(readFileSync(join(evidence, 'campaign.json'), 'utf8'));
    assert.equal(manifest.variants.find((row) => row.id === 'B02-source-anchored-selection').outcome.status, 'PASSED');
    assert.equal(manifest.variants.find((row) => row.id === 'final-source-witness').outcome.status, 'NOT_RUN');
    assert.deepEqual(manifest.sourceAtEnd, { status: 'BLOCKED', reason: 'SOURCE_CHANGED' });
    assert.notEqual(execution.status, 0, execution.stdout + execution.stderr);
    assert.match(execution.stdout + execution.stderr, /CAMPAIGN_FINAL_SOURCE_CHANGED/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
