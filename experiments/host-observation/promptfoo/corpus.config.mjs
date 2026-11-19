import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { casesForSuite, delegateModels, suites } from './corpus-cases.mjs';
import { exactAnswer } from './corpus-grading.mjs';
import { child } from './skill-provider.mjs';

const root = fileURLToPath(new URL('.', import.meta.url));
const hash = text => createHash('sha256').update(text).digest('hex');
const controls = process.env.KAST_CORPUS_CONTROLS === '1';
const cases = suites.flatMap(casesForSuite);

const provider = {
  id: () => controls ? 'corpus:grader-controls' : 'corpus:pytest-delegate',
  handlesOwnRetries: true,
  async callApi(prompt, context) {
    const { caseId, arm, model, control } = context.vars;
    const task = cases.find(row => row.id === caseId);
    if (!task || prompt !== task.task) return { error: 'Case identity mismatch' };
    if (controls) {
      const extra = task.nearMiss.find(value => !task.expected.includes(value));
      if (!extra) throw Error('Case needs a distinct distractor');
      const replies = { correct: [...task.expected].reverse(), nearMiss: task.nearMiss,
        omitted: task.expected.slice(1), extra: [...task.expected, extra], duplicate: [...task.expected, task.expected[0]] };
      return { output: JSON.stringify(replies[control]) };
    }
    if (!delegateModels.includes(model) || !['source', 'kast'].includes(arm)) return { error: 'Unknown delegate profile' };
    const directory = await mkdtemp(join(tmpdir(), 'kast-corpus-trial-'));
    try {
      const workspace = resolve(process.env.KAST_CORPUS_ROOT, task.workspace);
      const skillBody = arm === 'kast' ? await readFile(process.env.KAST_CORPUS_SKILL, 'utf8') : '';
      const participant = { workspace, model: 'openai-codex/' + model, arm, prompt, skillBody,
        revision: task.revision, repository: task.repository };
      await writeFile(join(directory, 'request.json'), JSON.stringify(participant), { mode: 0o600 });
      const run = await child('uv', ['run', '--locked', '--project', '..', 'pytest', '-q', '--noconftest',
        '-p', 'no:cacheprovider', 'corpus_trial.py::test_delegate'], {
        cwd: root, env: { ...process.env, PYTEST_DISABLE_PLUGIN_AUTOLOAD: '1', KAST_CORPUS_TRIAL: directory },
        timeout: 900_000, captureLimits: true,
      });
      if (run.status !== 0 || run.termination) {
        const failure = run.stdout.split('\n').filter(line => line.startsWith('E ')).at(-1);
        return { error: run.termination ?? failure?.trim().slice(0, 300) ?? 'Pytest delegate failed' };
      }
      const result = JSON.parse(await readFile(join(directory, 'response.json'), 'utf8'));
      return { ...result, metadata: { ...result.metadata, revision: task.revision, model, effort: 'low',
        taskSha256: hash(prompt), skillSha256: skillBody ? hash(skillBody) : null } };
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  },
};

function trial(task, vars) {
  const { id, expected } = task;
  return {
    description: [task.suite, id, vars.model, vars.arm, vars.control].filter(Boolean).join(' / '),
    vars: { caseId: id, task: task.task, suite: task.suite, comparison: 'upstream', cohort: vars.model ?? 'controls', ...vars },
    options: { disableVarExpansion: true },
    assert: [{ type: 'javascript', metric: controls ? 'grader' : 'correctness',
      value: (_output, ctx) => {
        const correct = exactAnswer(ctx.providerResponse.output, expected);
        const pass = controls ? correct === (vars.control === 'correct') : correct;
        return { pass, score: Number(pass), reason: correct ? 'Exact set' : 'Answer differs from the pinned set' };
      } }],
  };
}

export default {
  description: 'Full upstream Java and Kotlin workspaces with matched low-reasoning delegates',
  providers: [provider], prompts: ['{{task}}'], sharing: false,
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  tests: cases.flatMap((task, index) => controls
    ? ['correct', 'nearMiss', 'omitted', 'extra', 'duplicate'].map(control => trial(task, { control }))
    : delegateModels.flatMap((model, position) => ((index + position) % 2 ? ['kast', 'source'] : ['source', 'kast'])
      .map(arm => trial(task, { model, arm })))),
};
