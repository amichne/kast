export default {
  description: 'Paired Pi agent-efficiency trials with source and answer oracles',
  prompts: ['{{caseId}}'],
  providers: ['file://./agent-efficiency-provider.mjs'],
  scenarios: [{
    description: 'agent-efficiency',
    config: [{}],
    tests: [{ path: 'file://./agent-efficiency-provider.mjs:cases' }],
  }],
  extensions: [
    'file://./agent-efficiency-provider.mjs:beforeAll',
    'file://./agent-efficiency-provider.mjs:afterAll',
  ],
  defaultTest: { assert: [{ type: 'javascript', metric: 'correctness', value: 'file://./scenario-oracle.mjs' }] },
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  sharing: false,
};
