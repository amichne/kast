import { spawnSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { Type } from 'typebox';
import { permittedKast, runSkillKast, runtimeEnvironment } from './skill-sandbox.mjs';

export default function (pi: ExtensionAPI) {
  const workspace = process.env.KAST_EVAL_TRIAL_WORKSPACE!;
  const scope = process.env.KAST_EVAL_TRIAL_SCOPE!;
  if (process.env.KAST_EVAL_TRIAL_READER === '1') {
    pi.registerTool({
      name: 'search_kotlin_source',
      label: 'Search Kotlin source',
      description: 'Search literal text in Kotlin source below invocation. Return matching lines and workspace-relative paths.',
      parameters: Type.Object({ text: Type.String({ minLength: 1, maxLength: 120 }) }),
      async execute(_id, { text }) {
        const matches = readdirSync(join(workspace, 'invocation'), { recursive: true, encoding: 'utf8' })
          .filter(path => path.endsWith('.kt'))
          .flatMap(path => {
            const file = join('invocation', path);
            return readFileSync(join(workspace, file), 'utf8').split('\n')
              .filter(line => line.includes(text)).map(line => ({ file, line }));
          });
        return {
          content: [{ type: 'text', text: JSON.stringify(matches) }],
          details: { text, matches },
        };
      },
    });
  }
  const kast = process.env.KAST_EVAL_TRIAL_BIN;
  if (!kast) return;
  const documents = scope === 'repository';
  pi.registerTool({
    name: 'kast',
    label: 'Kast CLI',
    description: [
      'Run the installed Kast CLI with argv after kast.',
      ...(documents ? ['Pass a JSON request as the stdin string for argv ["query", "--request", "-"].'] : []),
    ].join(' '),
    parameters: Type.Object({
      argv: Type.Array(Type.String(), { minItems: 1, maxItems: 28 }),
      ...(documents ? { stdin: Type.Optional(Type.String({ maxLength: 8192 })) } : {}),
    }),
    async execute(_id, { argv, stdin }) {
      if (!permittedKast(argv, scope, stdin)) throw new Error('Command outside this trial');
      return runSkillKast(argv, stdin, (args: string[], input?: string) => {
        const result = spawnSync(kast, args, {
          cwd: workspace, env: runtimeEnvironment(), encoding: 'utf8', input,
          timeout: 60_000, maxBuffer: 4 * 1024 * 1024,
        });
        if (result.error) throw result.error;
        return result;
      });
    },
  });
}
