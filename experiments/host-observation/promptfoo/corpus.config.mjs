import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { casesForSuite, delegateModels, suites } from './corpus-cases.mjs';
import { exactAnswer } from './corpus-grading.mjs';
import { child } from './skill-provider.mjs';
import { corpusBaseline } from './corpus-source.mjs';
import { selectPortfolioCases } from './portfolio.mjs';
import { projectPiTrial } from './pi-accounting.mjs';
import { createTrialTiming } from './trial-phases.mjs';

const root = fileURLToPath(new URL('.', import.meta.url));
const hash = text => createHash('sha256').update(text).digest('hex');
const controls = process.env.KAST_CORPUS_CONTROLS === '1';
const selection = process.env.KAST_EVAL_PORTFOLIO ?? 'all';
const cases = selectPortfolioCases(suites.flatMap(casesForSuite), selection);
const requestedPanel = selection === 'curated' ? 'A' : 'RESTRICTED';

export function createCorpusProvider({ cases, controls = false, requestedPanel = 'RESTRICTED' }, effects = {}) {
  const { runChild = child, environment = process.env, cleanup = rm, clock } = effects;
  return {
    id: () => controls ? 'corpus:grader-controls' : 'corpus:pytest-delegate',
    handlesOwnRetries: true,
    async callApi(prompt, context) {
      const timing = createTrialTiming('CORPUS_PROVIDER', clock);
      let observation = projectPiTrial(undefined);
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
      if (requestedPanel === 'A' && !corpusBaseline.panelA.admitted) {
        return { ...observation, error: 'PANEL_A_BASELINE_UNQUALIFIED', metadata: {
          ...observation.metadata, admission: { state: 'BLOCKED', reason: corpusBaseline.panelA.reason },
          requestedPanel, panel: 'RESTRICTED', baseline: corpusBaseline, model, effort: 'low', arm,
          caseId, revision: task.revision, taskSha256: hash(prompt), timing: timing.snapshot(),
        } };
      }
      const metadata = { caseId, revision: task.revision, model, effort: 'low', arm, requestedPanel,
        panel: 'RESTRICTED', baseline: corpusBaseline, taskSha256: hash(prompt) };
      let directory;
      let settled = true;
      let stage = 'CORPUS_PREPARATION_FAILED';
      try {
        await timing.measure('preparation', async () => {
          directory = await mkdtemp(join(tmpdir(), 'kast-corpus-trial-'));
          const workspace = resolve(environment.KAST_CORPUS_ROOT, task.workspace);
          const skillBody = arm === 'kast' ? await readFile(environment.KAST_CORPUS_SKILL, 'utf8') : '';
          metadata.skillSha256 = skillBody ? hash(skillBody) : null;
          const participant = { workspace, model: 'openai-codex/' + model, arm, prompt, skillBody,
            revision: task.revision, repository: task.repository, caseId, requestedPanel };
          await writeFile(join(directory, 'request.json'), JSON.stringify(participant), { mode: 0o600 });
        });
        stage = 'CORPUS_DELEGATE_FAILED';
        const run = await timing.measure('participant', () => runChild('uv', ['run', '--locked', '--project', '..', 'pytest', '-q', '--noconftest',
          '-p', 'no:cacheprovider', 'corpus_trial.py::test_delegate'], {
          cwd: root, env: { ...environment, PYTEST_DISABLE_PLUGIN_AUTOLOAD: '1', KAST_CORPUS_TRIAL: directory },
          timeout: 900_000, captureLimits: true,
        }), result => result.status === 0 && !result.termination ? 'COMPLETED' : 'FAILED');
        // The receipt can exist even if the post-run workspace check rejected the attempt.
        const delivered = await readFile(join(directory, 'response.json'), 'utf8').catch(error => {
          if (error.code === 'ENOENT') return null;
          throw error;
        });
        if (delivered !== null) {
          stage = 'CORPUS_RECEIPT_REJECTED';
          const result = JSON.parse(delivered);
          if (!result || typeof result !== 'object' || Array.isArray(result)) throw Error(stage);
          const { timing: participantTiming, ...receiptMetadata } = result.metadata ?? {};
          observation = { ...observation, ...result, metadata: { ...observation.metadata, ...receiptMetadata,
            ...(participantTiming && { participantTiming }) } };
        }
        if (run.status !== 0 || run.termination) {
          const failure = run.stdout.split('\n').filter(line => line.startsWith('E ')).at(-1);
          observation.error = run.termination ?? failure?.trim().slice(0, 300) ?? 'Pytest delegate failed';
        } else if (delivered === null) {
          observation.error = 'CORPUS_RECEIPT_MISSING';
        }
        metadata.grade = await timing.measure('grading', () => {
          const pass = !observation.error && exactAnswer(observation.output, task.expected);
          return { pass, score: Number(pass), reason: observation.error ? 'Participant attempt is unassessed'
            : pass ? 'Exact set' : 'Answer differs from the pinned set' };
        });
      } catch (error) {
        settled = error?.code !== 'PI_CHILD_REAP_UNAVAILABLE';
        if (error?.trial) {
          const rejected = projectPiTrial(error.trial);
          observation = { ...observation, ...rejected, metadata: { ...observation.metadata, ...rejected.metadata } };
        }
        observation.error = settled ? stage : 'PI_CHILD_REAP_UNAVAILABLE';
      } finally {
        if (directory) {
          try {
            await timing.measure('cleanup', async () => {
              if (!settled) throw Error('PARTICIPANT_REAP_UNAVAILABLE');
              await cleanup(directory, { recursive: true, force: true });
            });
          } catch {
            metadata.cleanup = 'PARTICIPANT_CLEANUP_FAILED';
            observation.error ??= metadata.cleanup;
          }
        }
      }
      if (observation.error) metadata.grade = { pass: false, score: 0, reason: 'Participant attempt is unassessed' };
      return { ...observation, metadata: { ...observation.metadata, ...metadata, timing: timing.snapshot() } };
    },
  };
}

const provider = createCorpusProvider({ cases, controls, requestedPanel });

function trial(task, vars) {
  const { id, expected } = task;
  return {
    description: [task.suite, id, vars.model, vars.arm, vars.control].filter(Boolean).join(' / '),
    vars: { caseId: id, task: task.task, suite: task.suite, comparison: 'upstream',
      panel: controls ? 'CONTROL' : requestedPanel, cohort: vars.model ?? 'controls', ...vars },
    options: { disableVarExpansion: true },
    assert: [{ type: 'javascript', metric: controls ? 'grader' : 'correctness',
      value: (_output, ctx) => {
        if (!controls) return ctx.providerResponse.metadata?.grade ??
          { pass: false, score: 0, reason: 'Participant attempt is unassessed' };
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
