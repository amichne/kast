import { execFileSync } from 'node:child_process';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { Type } from 'typebox';
import { runtimeEnvironment } from './skill-sandbox.mjs';

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: 'read_previous_result',
    label: 'Read previous result',
    description: 'Read the preceding retained result through Kast. Returns the original response.',
    parameters: Type.Object({}, { additionalProperties: false }),
    async execute() {
      const argv = [
        'result',
        'read',
        '--ref',
        process.env.KAST_EVAL_RESULT_REF!,
        '--cursor',
        '0',
        '--output',
        'symbols',
        '--fields',
        'name',
        '--json',
      ];
      const text = execFileSync(process.env.KAST_EVAL_TRIAL_BIN!, argv, {
        env: runtimeEnvironment(),
        cwd: process.env.KAST_EVAL_TRIAL_WORKSPACE!,
        encoding: 'utf8',
        timeout: 60_000,
      });
      const reply = JSON.parse(text);
      return {
        content: [{ type: 'text', text }],
        details: {
          name: reply.document?.items?.[0]?.name,
          complete:
            reply.type === 'complete' &&
            reply.document.live?.root === process.env.KAST_EVAL_TRIAL_WORKSPACE &&
            reply.document.retention?.reference === process.env.KAST_EVAL_RESULT_REF &&
            reply.document.evidence_window?.type === 'FINAL' &&
            reply.document.items?.length === 1 &&
            reply.document.coverage?.exhaustive === true &&
            reply.document.original_evidence_summary?.failure_count === 0 &&
            reply.document.original_evidence_summary?.omission_count === 0,
        },
      };
    },
  });
}
