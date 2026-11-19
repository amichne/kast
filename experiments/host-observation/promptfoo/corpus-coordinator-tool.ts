import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { Type } from 'typebox';
import { recordCorpusDispatch } from './corpus-coordinators.mjs';

export default function (pi: ExtensionAPI) {
  const workspace = process.env.KAST_CORPUS_COORDINATOR_WORKSPACE!;
  const output = process.env.KAST_CORPUS_COORDINATOR_OUTPUT!;
  const config = process.env.KAST_CORPUS_COORDINATOR_CONFIG!;
  let dispatched = false;
  pi.registerTool({
    name: 'run_corpus_evaluation', label: 'Run the corpus evaluation',
    description: `Run the frozen Promptfoo evaluation for ${workspace} once and return only its aggregate outcome.`,
    parameters: Type.Object({}, { additionalProperties: false }),
    async execute() {
      const outcome = dispatched ? { status: 'INFRASTRUCTURE_FAILURE', reason: 'DISPATCH_ALREADY_USED' } : await dispatch();
      return { content: [{ type: 'text', text: JSON.stringify(outcome) }], details: outcome,
        isError: outcome.status === 'INFRASTRUCTURE_FAILURE' };
    },
  });

  async function dispatch() {
    dispatched = true;
    return recordCorpusDispatch(workspace, output, config);
  }
}
