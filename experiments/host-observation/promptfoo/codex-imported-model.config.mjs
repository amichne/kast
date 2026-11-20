import { codexDecisionProvider, decisionOnly } from './codex-decision-provider.mjs';
import { binary } from './skill-provider.mjs';
import {
  completeDeclarationPath, examineWorlds, gradeImportedAnswer, participantPrompt, qualifyWorlds, worlds,
} from './imported-model.mjs';

const root = process.env.KAST_EVAL_IMPORTED_MODEL_ROOT;
if (!root) throw Error('Set KAST_EVAL_IMPORTED_MODEL_ROOT to the prepared fixture root');
const { cases, sourceSha256 } = await examineWorlds(root);
const receipts = await qualifyWorlds(root, await binary(process.env.KAST_EVAL_KAST, 'kast'));
const arms = ['source', 'kast', 'materialized'];

export default {
  description: 'Identify the imported Kotlin declaration from tracked source and observed Gradle state',
  sharing: false,
  prompts: ['{{task}}'],
  providers: [await codexDecisionProvider()],
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  tests: worlds.flatMap((world, index) => [...arms.slice(index), ...arms.slice(0, index)].map(arm => {
    const { snapshot, workspace, selection } = cases[world];
    let task = participantPrompt(snapshot, arm === 'materialized' ? selection : undefined);
    if (arm === 'kast') {
      const path = completeDeclarationPath(receipts[world], workspace);
      task += '\nKast returned one declaration from the imported main source set with exhaustive coverage, zero failures and zero omissions:\n' + path;
    }
    return {
      description: `imported-main / ${world} / ${arm}`,
      metadata: { world, arm, sourceSha256 },
      vars: { task },
      options: { disableVarExpansion: true },
      assert: [{
        type: 'javascript', metric: 'resolvedDeclaration',
        value: (_output, context) => decisionOnly(context.providerResponse)
          ? gradeImportedAnswer(context.providerResponse.output, world)
          : { pass: false, score: 0, reason: 'Unexpected Codex turn evidence' },
      }],
    };
  })),
};
