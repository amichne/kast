export default {
  description: 'Installed semantic campaign with independent source witnesses',
  prompts: ['{{caseId}}'],
  providers: ['file://./semantic-campaign-provider.mjs'],
  scenarios: [{
    description: 'semantic-campaign',
    config: [{}],
    tests: [{ path: 'file://./semantic-campaign-provider.mjs:cases' }],
  }],
  extensions: [
    'file://./semantic-campaign-provider.mjs:beforeAll',
    'file://./semantic-campaign-provider.mjs:afterAll',
  ],
  defaultTest: { assert: [{ type: 'javascript', value: 'file://./scenario-oracle.mjs' }] },
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  sharing: false,
};
