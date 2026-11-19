import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { assessCorpusExport } from './corpus-coordinators.mjs';
import { casesForSuite, delegateModels, suites } from './corpus-cases.mjs';
import { exactAnswer } from './corpus-grading.mjs';

const digest = text => createHash('sha256').update(text).digest('hex');

function record(directory, workspace) {
  const file = readFileSync(join(directory, workspace, 'results.json'));
  const exported = JSON.parse(file);
  const dispatch = JSON.parse(readFileSync(join(directory, workspace, 'receipt.json')));
  const coordinator = JSON.parse(readFileSync(join(directory, workspace, 'coordinator.json')));
  if (coordinator.profile.workspace !== workspace || coordinator.coordinator !== 'COMPLETED') {
    throw Error('Coordinator did not complete the requested suite');
  }
  const evaluation = assessCorpusExport(exported, workspace, dispatch.exitCode);
  if (evaluation.status !== dispatch.status || evaluation.reason !== dispatch.reason) {
    throw Error('Dispatch and export disagree for ' + workspace);
  }
  const tasks = casesForSuite(workspace);
  const planned = new Set(tasks.flatMap(task => delegateModels
    .flatMap(model => ['source', 'kast'].map(arm => JSON.stringify([task.id, model, arm])))));
  const rows = exported.results.results.map(row => {
    const { caseId, model, arm } = row.vars;
    const task = tasks.find(task => task.id === caseId);
    if (!planned.delete(JSON.stringify([caseId, model, arm]))) throw Error('Unexpected or duplicate trial');
    if (!task || task.task !== row.prompt.raw || row.response?.cached) throw Error('Trial inputs differ');
    const { metadata, tokenUsage, error, output } = row.response;
    if (!error && (metadata.revision !== task.revision || metadata.taskSha256 !== digest(task.task) ||
        metadata.model !== model || metadata.effort !== 'low' || metadata.arm !== arm ||
        (arm === 'source' ? metadata.skillSha256 !== null : !/^[0-9a-f]{64}$/.test(metadata.skillSha256)) ||
        row.success !== exactAnswer(output, task.expected))) throw Error('Trial evidence differs');
    const { elapsedMillis, toolCalls, toolCounts, completeKastCalls, semanticReceipts, semanticReceiptsOmitted,
      toolFailures, toolFailuresOmitted, terminalCause, skillSha256, taskSha256, systemSha256 } = metadata ?? {};
    return { caseId, model, arm, outcome: error ? 'error' : row.success ? 'correct' : 'incorrect',
      ...(error ? { error, terminalCause } : { answer: output }), totalTokens: tokenUsage?.total ?? null,
      elapsedMillis, toolCalls, toolCounts, completeKastCalls, taskSha256, systemSha256, skillSha256,
      semanticReceipts, semanticReceiptsOmitted, toolFailures, toolFailuresOmitted };
  });
  if (planned.size) throw Error('Missing planned trials for ' + workspace);
  return { suite: workspace, workspace: tasks[0].workspace, revision: tasks[0].revision, evalId: exported.evalId,
    sha256: digest(file), coordinator, environment: exported.metadata, evaluation, rows };
}

if (process.argv.length < 3) throw Error('Usage: node corpus-report.mjs RUN_DIRECTORY [RUN_DIRECTORY ...]');
const rounds = process.argv.slice(2).map(path => {
  const directory = resolve(path);
  if (existsSync(join(directory, 'results.json'))) return [record(dirname(directory), basename(directory))];
  return suites.map(workspace => record(directory, workspace));
});
const outcomes = { bothCorrect: 0, bothIncorrect: 0, sourceOnlyCorrect: 0, kastOnlyCorrect: 0, incomplete: 0 };
const jointlyCorrectTokens = { source: 0, kast: 0 };
const skillDigests = new Set();
for (const suite of rounds.flat()) {
  const pairs = new Map();
  for (const row of suite.rows) {
    if (row.skillSha256) skillDigests.add(row.skillSha256);
    const key = JSON.stringify([row.caseId, row.model]);
    if (!pairs.has(key)) pairs.set(key, {});
    pairs.get(key)[row.arm] = row;
  }
  for (const { source, kast } of pairs.values()) {
    if (source.outcome === 'error' || kast.outcome === 'error') outcomes.incomplete++;
    else if (source.outcome === 'correct' && kast.outcome === 'correct') {
      outcomes.bothCorrect++;
      for (const row of [source, kast]) {
        if (!Number.isSafeInteger(row.totalTokens) || row.totalTokens <= 0) throw Error('Missing matched token usage');
        jointlyCorrectTokens[row.arm] += row.totalTokens;
      }
    } else if (source.outcome === 'correct') outcomes.sourceOnlyCorrect++;
    else if (kast.outcome === 'correct') outcomes.kastOnlyCorrect++;
    else outcomes.bothIncorrect++;
  }
}
if (skillDigests.size !== 1) throw Error('Kast skill identity differs across the comparison');
console.log(JSON.stringify({ outcomes, jointlyCorrectTokens, rounds }, null, 2));
