import { armCapabilities, originalCases, skillCases } from './skill-cases.mjs';
import { gradeSkill } from './skill-grading.mjs';

export default {
  description: 'Kast skill and tool comparisons in isolated Pi sessions',
  prompts: ['{{task}}'],
  providers: ['file://./skill-provider.mjs'],
  evaluateOptions: { maxConcurrency: 1, cache: false },
  scenarios: Object.entries(skillCases).flatMap(([caseId, scenario]) => {
    const comparisons = caseId === 'source-lookup' ? ['tool'] : ['skill'];
    if (originalCases.includes(caseId)) comparisons.push('skill-version');
    return comparisons.map(comparison => ({
      description: `${caseId} / ${comparison}`,
      config: [{ vars: { caseId, comparison, task: scenario.prompt } }],
      tests: Object.entries(armCapabilities)
        .filter(([, capabilities]) => capabilities.comparison === comparison)
        .map(([arm]) => ({
          description: `[skill] ${caseId} / ${arm}`,
          vars: { arm },
          assert: [{
            type: 'javascript', metric: 'correctness',
            value: (output, context) => gradeSkill(caseId, output, context.providerResponse?.metadata),
          }],
        })),
    }));
  }),
};
