import { developerCases } from './developer-cases.mjs';
import { gradeWorkspace, prepareWorkspace, preflightOracle } from './developer-oracle.mjs';
import { createDeveloperProvider, developerArms, gradeDeveloperResponse } from './developer-provider.mjs';

export default {
  description: 'Matched Kotlin developer tasks with and without Kast',
  sharing: false,
  providers: [createDeveloperProvider({ cases: developerCases, prepareWorkspace, gradeWorkspace, preflightOracle })],
  prompts: ['{{task}}'],
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  scenarios: developerCases.map((task, index) => ({
    description: task.id,
    config: [{ vars: { caseId: task.id, comparison: 'developer-tasks', task: task.prompt } }],
    tests: [...developerArms.slice(index % developerArms.length), ...developerArms.slice(0, index % developerArms.length)].map(arm => ({
      description: `${task.id} / ${arm}`,
      vars: { arm },
      options: { disableVarExpansion: true },
      assert: [{ type: 'javascript', metric: 'correctness', value: (_output, context) => gradeDeveloperResponse(context.providerResponse) }],
    })),
  })),
};
