import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runIsolatedPi } from './skill-provider.mjs';
import { loadKastGuidance } from './kast-guidance.mjs';
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
  const directory = await mkdtemp(join(tmpdir(), 'kast-access-'));
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
    });
    return {
      ...(trial.error ? { error: trial.error } : { output: trial.reply }),
      tokenUsage: {
        total: trial.usage.total,
        prompt: trial.usage.input + trial.usage.cacheRead + trial.usage.cacheWrite,
        completion: trial.usage.output,
        cached: trial.usage.cacheRead,
      },
      metadata: {
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
