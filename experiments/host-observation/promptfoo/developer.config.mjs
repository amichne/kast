import { developerCases } from './developer-cases.mjs';
import { gradeWorkspace, prepareWorkspace, preflightOracle } from './developer-oracle.mjs';
import { createDeveloperProvider, developerArms, gradeDeveloperResponse } from './developer-provider.mjs';
import { selectPortfolioCases } from './portfolio.mjs';

const cases = selectPortfolioCases(developerCases, process.env.KAST_EVAL_PORTFOLIO);

export default {
  description: 'Matched Kotlin developer tasks with and without Kast',
  sharing: false,
  providers: [createDeveloperProvider({ cases, prepareWorkspace, gradeWorkspace, preflightOracle })],
  prompts: ['{{task}}'],
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  scenarios: cases.map((task, index) => ({
    description: task.id,
    config: [{ vars: { caseId: task.id, comparison: 'developer-tasks', panel: 'A', task: task.prompt } }],
    tests: [...developerArms.slice(index % developerArms.length), ...developerArms.slice(0, index % developerArms.length)].map(arm => ({
      description: `${task.id} / ${arm}`,
      vars: { arm },
      options: { disableVarExpansion: true },
      assert: [{ type: 'javascript', metric: 'correctness', value: (_output, context) => gradeDeveloperResponse(context.providerResponse) }],
    })),
  })),
};
