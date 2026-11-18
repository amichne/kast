import { spawnSync } from 'node:child_process';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { Type } from 'typebox';
import { completeDeclarationPath, queryArgs } from './imported-model.mjs';
import { runtimeEnvironment } from './skill-sandbox.mjs';

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: 'query_imported_main',
    label: 'Query imported Gradle main sources',
    description: 'Use Kast to find formatInvoiceReference in the imported Gradle main source set. Return its location and query coverage.',
    parameters: Type.Object({}, { additionalProperties: false }),
    async execute() {
      const workspace = process.env.KAST_EVAL_TRIAL_WORKSPACE!;
      const result = spawnSync(process.env.KAST_EVAL_TRIAL_BIN!, [...queryArgs], {
        cwd: workspace, env: runtimeEnvironment(), encoding: 'utf8', timeout: 60_000,
      });
      let path: string | null = null;
      if (!result.error && result.status === 0) {
        try { path = completeDeclarationPath(JSON.parse(result.stdout), workspace); } catch {}
      }
      const evidence = path ? { type: 'COMPLETE', path } : { type: 'QUERY_EVIDENCE_UNAVAILABLE' };
      return {
        content: [{ type: 'text', text: JSON.stringify(evidence) }],
        details: evidence,
        isError: path === null,
      };
    },
  });
}
