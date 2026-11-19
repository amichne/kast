import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { promisify } from 'node:util';
import { prepareFixtureWorkspace } from './skill-provider.mjs';
import { fixturePaths } from './skill-sandbox.mjs';

test('an imported fixture admits the Gradle cache and rejects new project scripts', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'project-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const workspace = await prepareFixtureWorkspace(directory);
  const cache = join(workspace, '.gradle');
  await mkdir(join(cache, 'kotlin-dsl'), { recursive: true });
  await writeFile(join(cache, 'kotlin-dsl', 'cache.gradle.kts'), '// Gradle cache entry\n');
  const module = join(workspace, 'invocation');
  await mkdir(join(module, '.gradle'));
  await writeFile(join(module, '.gradle', 'Unrelated.kt'), '// Gradle cache entry\n');
  assert.equal(await prepareFixtureWorkspace(directory), workspace);
  assert.deepEqual(await fixturePaths(module, name => name.endsWith('.kt'), 'Kotlin'), [
    join('src', 'main', 'kotlin', 'fixture', 'staticcallbacks', 'invocation', 'Invocation.kt'),
  ]);

  const extra = join(workspace, 'invocation', 'extra.gradle.kts');
  await writeFile(extra, '// extra project input\n');
  await assert.rejects(prepareFixtureWorkspace(directory), /Gradle scripts differ/);
  await rm(extra);
  await rm(cache, { recursive: true });
  await symlink(join(workspace, 'invocation'), cache, 'dir');
  await assert.rejects(prepareFixtureWorkspace(directory), /Gradle paths must be regular files/);
});

test('only the Kast arm needs a Kast executable before the participant starts', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'project-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const workspace = await prepareFixtureWorkspace(directory);
  const provider = new URL('./skill-provider.mjs', import.meta.url).href;
  const cases = new URL('./skill-cases.mjs', import.meta.url).href;
  const script = `import SkillProvider from ${JSON.stringify(provider)};
    import { skillCases } from ${JSON.stringify(cases)};
    const caseId = 'source-lookup';
    const vars = { caseId, comparison: 'guided-kast-access', arm: process.argv[1] };
    const result = await new SkillProvider().callApi(skillCases[caseId].prompt, { vars });
    console.log(JSON.stringify(result.error));`;
  const env = { ...process.env, KAST_EVAL_WORKSPACE: workspace,
    KAST_EVAL_KAST: join(directory, 'missing-kast'), KAST_EVAL_PI: join(directory, 'missing-pi') };
  for (const [arm, missing] of [['with-kast', 'kast'], ['without-kast', 'pi']]) {
    const { stdout } = await promisify(execFile)(process.execPath, ['--input-type=module', '-e', script, arm], { env });
    assert.equal(JSON.parse(stdout), 'Set the executable path for ' + missing, arm);
  }
});
