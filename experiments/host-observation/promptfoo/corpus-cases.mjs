import { readFileSync } from 'node:fs';

export const workspaces = ['ktfmt', 'coroutines', 'spring'];
export const suites = [...workspaces, 'spring-holdout'];
const delegateProviders = Object.freeze({
  'gpt-6-luna': 'openai-codex', 'gpt-6.1-terra': 'openai-codex', 'gpt-6.2-sol': 'openai-codex',
  'claude-sonnet-5-5': 'claude-code',
});
export const delegateModels = Object.keys(delegateProviders);
export function delegatePiModel(model) {
  const provider = Object.hasOwn(delegateProviders, model) ? delegateProviders[model] : undefined;
  if (!provider) throw Error('Unknown delegate model');
  return provider + '/' + model;
}

const read = name => JSON.parse(readFileSync(new URL(name, import.meta.url), 'utf8'));

export function casesForSuite(suite) {
  if (!suites.includes(suite)) throw Error('Unknown corpus suite');
  const workspace = suite === 'spring-holdout' ? 'spring' : suite;
  const corpus = read('./' + workspace + '.cases.json');
  if (workspace !== corpus.workspace) throw Error('Corpus workspace mismatch');
  const tasks = suite === 'spring-holdout' ? [read('./spring-holdout.json')] : corpus.cases;
  return tasks.map(task => ({ ...task, workspace, suite, repository: corpus.repository, revision: corpus.revision }));
}
