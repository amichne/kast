import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { Type } from 'typebox';

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: 'read_kast_guidance',
    label: 'Read Kast guidance',
    description: 'Read the supplied Kast skill or its linked CLI guide.',
    parameters: Type.Object({ path: Type.Union([
      Type.Literal('skills/kast/SKILL.md'), Type.Literal('skills/kast/public-cli.md'),
    ]) }, { additionalProperties: false }),
    async execute(_id, { path }, _signal, _update, ctx) {
      if (path !== 'skills/kast/SKILL.md' && path !== 'skills/kast/public-cli.md') throw Error('Unknown Kast guidance file');
      return { content: [{ type: 'text', text: readFileSync(join(ctx.cwd, path), 'utf8') }], details: { path } };
    },
  });
}
