import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const canonical = new URL('../../../agent-tools/skills/kast/', import.meta.url);
export const kastInstruction = 'Kast is available for compiler-backed analysis and symbol resolution in Kotlin code and Gradle projects. Use the available tools when helpful; a correct solution does not require a Kast call.';
const digest = text => createHash('sha256').update(text).digest('hex');

export function loadKastGuidance({
  skillText = readFileSync(new URL('SKILL.md', canonical), 'utf8'),
  guideText = readFileSync(new URL('public-cli.md', canonical), 'utf8'),
} = {}) {
  if (!skillText.trim() || !guideText.trim()) throw Error('Incomplete Kast guidance');
  return {
    text: kastInstruction + ' Read skills/kast/SKILL.md for usage.',
    skillText, guideText, skillSha256: digest(skillText), guideSha256: digest(guideText),
  };
}

export async function stageKastGuidance(directory, guidance) {
  const root = join(directory, 'skills', 'kast');
  await mkdir(root, { recursive: true, mode: 0o700 });
  for (const [name, contents] of [['SKILL.md', guidance.skillText], ['public-cli.md', guidance.guideText]]) {
    await writeFile(join(root, name), contents, { flag: 'wx', mode: 0o400 });
  }
}
