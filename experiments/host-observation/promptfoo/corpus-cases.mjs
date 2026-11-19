import { readFileSync } from 'node:fs';

export const workspaces = ['ktfmt', 'coroutines', 'spring'];
export const suites = [...workspaces, 'spring-holdout'];
export const delegateModels = ['gpt-6-luna', 'gpt-6.1-terra', 'gpt-6.2-sol'];

const read = name => JSON.parse(readFileSync(new URL(name, import.meta.url), 'utf8'));

export function casesForSuite(suite) {
  if (!suites.includes(suite)) throw Error('Unknown corpus suite');
  const workspace = suite === 'spring-holdout' ? 'spring' : suite;
  const corpus = read('./' + workspace + '.cases.json');
  if (workspace !== corpus.workspace) throw Error('Corpus workspace mismatch');
  const tasks = suite === 'spring-holdout' ? [read('./spring-holdout.json')] : corpus.cases;
  return tasks.map(task => ({ ...task, workspace, suite, repository: corpus.repository, revision: corpus.revision }));
}
