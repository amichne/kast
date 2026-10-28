import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { join } from 'node:path';
import { test } from 'node:test';
import { SourceTextModule, SyntheticModule } from 'node:vm';

const source = readFileSync(new URL('../../copilot/task-extension.mjs', import.meta.url), 'utf8');
const catalog = JSON.parse(readFileSync(process.env.KAST_TASK_TEST_CATALOG, 'utf8'));

test('Copilot registers the shared catalog and delegates exact task arguments and outcomes', async () => {
  let registered;
  const pending = [
    { args: ['catalog'], input: '', result: catalog },
    { args: ['call', 'find'], input: JSON.stringify({ name: 'Thing', source_sets: ['main'] }),
      result: { content: [{ type: 'text', text: '{ "type": "qualified", "document": {"reason":"fixture"} }\n' }], isError: true } },
    { args: ['call', 'source'], input: JSON.stringify({ ref: 'exact:v5:AAAAAAAAAAAAAAAAAAAAAA' }),
      result: { content: [{ type: 'text', text: '{"type":"complete","document":{}}\n' }], isError: false } },
    { args: ['call', 'find'], input: '{}', result: { content: [], isError: 'false' } },
  ];
  const failures = [];
  const spawn = (command, args, options) => {
    const expected = pending.shift();
    try {
      assert.ok(expected, 'unexpected child call');
      assert.equal(command, 'uv');
      assert.deepEqual(args, ['run', '--locked', '--script', '/fixture home/.local/share/kast/installation/share/kast/agent-tools/plugins/kast/skills/kast/scripts/server.py', ...expected.args]);
      assert.equal(options.cwd, process.cwd());
      assert.equal(options.killSignal, 'SIGINT');
    } catch (error) { failures.push(error); throw error; }
    const child = new EventEmitter();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.stdin = new PassThrough();
    const parts = [];
    child.stdin.on('data', part => parts.push(part));
    child.stdin.on('finish', () => {
      try { assert.equal(Buffer.concat(parts).toString(), expected.input); }
      catch (error) { failures.push(error); }
      child.stdout.end(JSON.stringify(expected.result));
      child.emit('close', 0);
    });
    return child;
  };
  const exports = {
    'node:child_process': { spawn }, 'node:os': { homedir: () => '/fixture home' }, 'node:path': { join },
    '@github/copilot-sdk/extension': { joinSession: options => { registered = options.tools; } },
  };
  const module = new SourceTextModule(source);
  await module.link(specifier => {
    const values = exports[specifier];
    assert.ok(values, 'unexpected module ' + specifier);
    return new SyntheticModule(Object.keys(values), function () {
      for (const [name, value] of Object.entries(values)) this.setExport(name, value);
    });
  });
  await module.evaluate();
  assert.deepEqual(registered.map(tool => ({ name: tool.name, inputSchema: tool.parameters })),
    catalog.tools.map(tool => ({ name: tool.name, inputSchema: tool.inputSchema })));
  assert.ok(!registered.some(tool => tool.name === 'query_symbols'));
  const find = registered.find(tool => tool.name === 'find');
  const result = await find.handler({ name: 'Thing', source_sets: ['main'] });
  assert.deepEqual(result, { resultType: 'failure', textResultForLlm: '{ "type": "qualified", "document": {"reason":"fixture"} }\n' });
  const sourceTool = registered.find(tool => tool.name === 'source');
  assert.deepEqual(await sourceTool.handler({ ref: 'exact:v5:AAAAAAAAAAAAAAAAAAAAAA' }),
    { resultType: 'success', textResultForLlm: '{"type":"complete","document":{}}\n' });
  await assert.rejects(find.handler({}), /KAST_TASK_INVALID_REPLY/);
  assert.deepEqual(failures, []);
  assert.deepEqual(pending, [], 'unconsumed child calls');
});
