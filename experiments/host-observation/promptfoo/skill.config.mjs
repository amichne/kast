import { armCapabilities, guidedCases, originalCases, skillCases } from './skill-cases.mjs';
import { gradeSkill } from './skill-grading.mjs';

export default {
  description: 'Canonical Kast skill trials, skill revisions, and guided Kast access',
  prompts: ['{{task}}'],
  providers: ['file://./skill-provider.mjs'],
  evaluateOptions: { maxConcurrency: 1, cache: false },
  scenarios: Object.entries(skillCases).flatMap(([caseId, scenario]) => {
    const comparisons = guidedCases.has(caseId) ? ['guided-kast-access'] : ['canonical-skill'];
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
