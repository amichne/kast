import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { Type } from 'typebox';

const commands = new Set(['find', 'references', 'trace', 'result', 'source', 'walk', 'schema']);
const options = new Set([
  '--kind',
  '--directory',
  '--source-set',
  '--ref',
  '--depth',
  '--bytes',
  '--cursor',
  '--evidence-cursor',
  '--output',
  '--region',
  '--context',
  '--definition',
  '--production-only',
  '--help',
]);

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: 'preview',
    label: 'Preview a contract-17 request',
    description:
      'Call the historical task CLI with argv, for example ["find","--help"]. It prints the admitted JSON without a Host call.',
    parameters: Type.Object({
      argv: Type.Array(Type.String({ maxLength: 256 }), { minItems: 1, maxItems: 24 }),
    }),
    async execute(_id, { argv }) {
      if (!commands.has(argv[0]) || argv.slice(1).some((arg) => arg.startsWith('-') && !options.has(arg))) {
        throw Error('Unsupported preview argument');
      }
      const root = process.env.KAST_TASK_ACCESS_DIR!;
      const args = [
        'run',
        '--locked',
        '--script',
        join(root, 'access.py'),
        ...argv,
        '--catalog-file',
        join(root, 'catalog.json'),
        '--emit-request',
      ];
      try {
        const text = execFileSync('uv', args, {
          cwd: root,
          encoding: 'utf8',
          timeout: 30000,
          maxBuffer: 256 * 1024,
        });
        return { content: [{ type: 'text', text }], details: { argv, status: 0 } };
      } catch (error) {
        const failure = error as { stdout?: string; status?: number };
        if (failure.status !== 2 || !failure.stdout) throw error;
        return { content: [{ type: 'text', text: failure.stdout }], details: { argv, status: 2 } };
      }
    },
  });
}
