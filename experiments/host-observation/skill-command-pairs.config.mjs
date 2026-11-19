// The experiment report pins the Promptfoo suite that supplies the Pi provider and planner.
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

const suite = process.env.KAST_EVAL_HARNESS ?? fileURLToPath(new URL('./promptfoo/', import.meta.url));
const moduleUrl = name => pathToFileURL(join(suite, name)).href;
const { binary, child, runIsolatedPi } = await import(moduleUrl('skill-provider.mjs'));
const { loadKastGuidance } = await import(moduleUrl('kast-guidance.mjs'));
const { planSkillDraft } = await import(moduleUrl('skill-grading.mjs'));
const { parseFlags, runtimeEnvironment } = await import(moduleUrl('skill-sandbox.mjs'));
const { skillCases } = await import(moduleUrl('skill-cases.mjs'));

const reference = ['kast', 'query', '--ref', 'EXACT_REF'];
const page = ['kast', 'result', 'read', '--ref', 'RESULT_REF', '--cursor', 'CURSOR'];
const tasks = [
  {
    id: 'incoming-callers',
    task: skillCases.callees.prompt.replace('declarations called by', 'declarations that call'),
    controls: {
      correct: [...reference, '--relation', 'callers'],
      wrong: [...reference, '--relation', 'callees'],
      omitted: reference,
      extra: [...reference, '--relation', 'callers', '--relation', 'callees'],
    },
  },
  {
    id: 'retained-occurrences',
    task: skillCases.retained.prompt.replace('kind symbols', 'kind occurrences'),
    controls: {
      correct: [...page, '--output', 'occurrences'],
      wrong: [...page, '--output', 'symbols'],
      omitted: page,
      extra: [...page, '--output', 'occurrences', '--output', 'symbols'],
    },
  },
];

function matches(id, answer, planned) {
  if (id === 'incoming-callers') {
    return isDeepStrictEqual(planned?.from, { type: 'references', values: [{ token: 'EXACT_REF' }] }) &&
      isDeepStrictEqual(planned?.steps, [{ type: 'related', relation: 'callers' }]) &&
      planned?.output?.type === 'symbols';
  }
  if (id !== 'retained-occurrences') throw Error('Unknown case');
  let argv;
  try {
    argv = JSON.parse(answer);
  } catch {
    return false;
  }
  if (!Array.isArray(argv) || !argv.every(value => typeof value === 'string') ||
      !isDeepStrictEqual(argv.slice(0, 3), ['kast', 'result', 'read'])) return false;
  const flags = parseFlags(argv.slice(3), new Set(['--ref', '--cursor', '--output']));
  return isDeepStrictEqual(flags?.get('--ref'), ['RESULT_REF']) &&
    isDeepStrictEqual(flags?.get('--cursor'), ['CURSOR']) &&
    isDeepStrictEqual(flags?.get('--output'), ['occurrences']);
}

async function liveReply(prompt, kast, directory) {
  const skill = process.env.KAST_EVAL_SKILL ?? fileURLToPath(new URL('../../agent-tools/skills/kast/', import.meta.url));
  const guidance = loadKastGuidance({
    skillText: await readFile(join(skill, 'SKILL.md'), 'utf8'),
    guideText: await readFile(join(skill, 'public-cli.md'), 'utf8'),
  });
  const model = process.env.KAST_EVAL_PI_MODEL ?? 'openai-codex/gpt-6.1-sol';
  const trial = await runIsolatedPi({
    directory, prompt, guidance, model, effort: 'low', toolNames: ['kast'],
    extension: resolve(suite, 'skill-tool.ts'),
    extensionEnvironment: {
      KAST_EVAL_TRIAL_WORKSPACE: directory,
      KAST_EVAL_TRIAL_SCOPE: 'draft',
      KAST_EVAL_TRIAL_BIN: kast,
    },
  });
  if (trial.error) return { error: trial.error };
  return {
    output: trial.reply,
    metadata: { model, effort: 'low', usage: trial.usage, toolCalls: trial.toolCalls, ...trial.guidance },
  };
}

const controls = process.env.KAST_EVAL_CONTROLS === '1';
async function callApi(prompt, { vars }) {
  const task = tasks.find(task => task.id === vars.id);
  if (!task || prompt !== task.task) return { error: 'Unknown command task' };
  const kast = await binary(process.env.KAST_EVAL_KAST, 'kast');
  const directory = await mkdtemp(join(tmpdir(), 'kast-command-pair-'));
  try {
    const reply = controls
      ? { output: JSON.stringify(task.controls[vars.control]) }
      : await liveReply(prompt, kast, directory);
    if (reply.error || task.id !== 'incoming-callers') return reply;
    const planned = await planSkillDraft('callees', reply.output, (args, input) =>
      child(kast, ['query', 'plan', ...args], { cwd: directory, env: runtimeEnvironment(), timeout: 10_000, input }));
    return { ...reply, metadata: { ...reply.metadata, planned } };
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

export default {
  description: 'One incoming call hop and the next retained occurrence page',
  sharing: false,
  prompts: ['{{task}}'],
  providers: [{ id: () => controls ? 'offline:command-pairs' : 'pi:command-pairs', handlesOwnRetries: true, callApi }],
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  tests: tasks.flatMap(({ id, task, controls: examples }) => (controls ? Object.keys(examples) : [undefined]).map(control => ({
    description: control ? `${id}/${control}` : id,
    vars: control ? { id, task, control } : { id, task },
    assert: [{
      type: 'javascript', metric: 'correctness',
      value: (output, ctx) => matches(id, output, ctx.providerResponse?.metadata?.planned),
    }],
  }))),
};
