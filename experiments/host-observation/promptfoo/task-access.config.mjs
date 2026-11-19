import { rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runIsolatedPi } from './skill-provider.mjs';
import { projectPiTrial } from './pi-accounting.mjs';
import { loadKastGuidance } from './kast-guidance.mjs';
import { createParticipantDirectory } from './participant-surface.mjs';
import { gradeAccess, historicalFile, questions, revision, schemaText } from './task-access.mjs';

const system =
  'Draft one contract-17 query. Return only JSON matching the task. Every reference here is illustrative. Do not send a semantic request.';
const extension = fileURLToPath(new URL('./task-access-preview.ts', import.meta.url));
const model = process.env.KAST_EVAL_TASK_MODEL ?? 'openai-codex/gpt-6.1-terra';
const skill = historicalFile('experiments/task-access/SKILL.md');
const catalog = {
  type: 'catalog',
  catalog: {
    schemaVersion: 17,
    tools: [
      {
        name: 'query_symbols',
        effect: 'READ',
        description: 'Read a Kotlin query',
        inputSchema: JSON.parse(schemaText),
      },
    ],
  },
};

async function callApi(prompt, context) {
  const directory = await createParticipantDirectory();
  let observation = projectPiTrial(undefined);
  try {
    const withCli = context.vars.arm === 'task-cli';
    if (withCli) {
      for (const file of ['access.py', 'access.py.lock', 'transport.py']) {
        await writeFile(join(directory, file), historicalFile('experiments/task-access/' + file));
      }
      await writeFile(join(directory, 'catalog.json'), JSON.stringify(catalog));
    }
    const trial = await runIsolatedPi({
      directory,
      model,
      effort: 'high',
      prompt,
      system: [system, schemaText, ...(withCli ? [skill] : [])].join('\n'),
      ...(withCli ? { guidance: loadKastGuidance() } : {}),
      extension: withCli ? extension : undefined,
      extensionEnvironment: { KAST_TASK_ACCESS_DIR: directory },
      toolNames: withCli ? ['preview'] : [],
      toolUsagePolicy: 'no-model-helpers',
    });
    observation = projectPiTrial(trial);
    return {
      ...observation,
      ...(trial.error ? { error: trial.error } : { output: trial.reply }),
      metadata: {
        ...observation.metadata,
        model,
        effort: 'high',
        revision,
        evidenceKind: 'HISTORICAL_CONTRACT_DRAFT',
        ...(trial.guidance ? { guidance: {mode:'STAGED',preloadedBytes:0,...trial.guidance}, systemSha256: trial.systemSha256 } : {}),
        toolCalls: trial.toolProposals,
        usage: trial.usage,
        previewCalls: trial.calls.filter(call => call.tool === 'preview'),
      },
    };
  } catch (error) {
    const preserved = error?.trial ? projectPiTrial(error.trial) : observation;
    return { ...preserved, error: 'TASK_ACCESS_TRIAL_UNAVAILABLE' };
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

export default {
  description: 'Historical contract-17 draft and preview with no semantic execution',
  sharing: false,
  providers: [{ id: () => 'kast:task-access', handlesOwnRetries: true, callApi }],
  prompts: ['{{task}}'],
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  scenarios: Object.entries(questions).map(([caseId, task], i) => ({
    description: caseId,
    config: [{ vars: { caseId, task, comparison: 'contract-17' } }],
    tests: (i % 2 === 0 ? ['schema', 'task-cli'] : ['task-cli', 'schema']).map((arm) => ({
      description: 'contract-17 ' + caseId + ' / ' + arm,
      vars: { arm },
      assert: [{ type: 'javascript', metric: 'correctness', value: (output) => gradeAccess(caseId, output) }],
    })),
  })),
};
