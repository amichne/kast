import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { loadKastGuidance, stageKastGuidance } from './promptfoo/kast-guidance.mjs';
import { GuidanceReadTool } from './pi_evaluation_policy.mjs';

export { loadKastGuidance };
const paths = ['skills/kast/SKILL.md', 'skills/kast/public-cli.md'];
const digest = value => createHash('sha256').update(value).digest('hex');

export function replayGuidanceIdentity(guidance) {
  return digest(JSON.stringify([guidance.text, guidance.skillText, guidance.guideText]));
}

export function replayGuidance(plan, required = false) {
  if (plan.kastGuidanceSha256 === undefined) {
    if (required) throw Error('REPLAY_GUIDANCE_PIN_REQUIRED');
    return undefined;
  }
  const guidance = loadKastGuidance();
  if (replayGuidanceIdentity(guidance) !== plan.kastGuidanceSha256) throw Error('REPLAY_GUIDANCE_CHANGED');
  return guidance;
}

export function sdkGuidanceInstructions(guidance, mode, hasReadBudget = false) {
  const scope = mode === 'replay'
    ? 'This replay uses a fixture. Use its supplied Bash contract and synthetic evidence.'
    : mode === 'received-result'
      ? 'Interpret the received Kast result.'
      : 'Use the supplied Kast MCP tools for the requested semantic work.';
  return [guidance.text, scope, ...(!hasReadBudget ? [guidance.skillText] : [])].join('\n\n');
}

export function appendContinuationGuidance(payload, instructions) {
  if (typeof payload.instructions !== 'string') return false;
  if (!payload.instructions.includes(instructions)) payload.instructions += `\n\n${instructions}`;
  return true;
}

export async function prepareSdkGuidance(directory, guidance, { mode, policy }) {
  await stageKastGuidance(directory, guidance);
  const work = policy.config.work;
  const semantic = policy.config.expectedSemanticCalls;
  const skillBytes = Buffer.byteLength(guidance.skillText);
  const hasReadBudget = mode === 'fresh' && work.tools > semantic && work.requests > semantic &&
    policy.maximumProviderRequests >= semantic + 2 &&
    Math.max(skillBytes,Buffer.byteLength(guidance.guideText)) <= policy.config.maximumToolTextBytes;
  const instructions = sdkGuidanceInstructions(guidance,mode,hasReadBudget);
  const tool = hasReadBudget ? {
    name: GuidanceReadTool,
    label: 'Read Kast guidance',
    description: 'Read one staged Kast skill file by its exact path.',
    parameters: { type: 'object', properties: { path: { type: 'string', enum: paths } }, required: ['path'], additionalProperties: false },
    async execute(_id, input) {
      if (!paths.includes(input?.path)) throw Error('KAST_GUIDANCE_PATH_REJECTED');
      const text = await readFile(join(directory, input.path), 'utf8');
      return { content: [{ type: 'text', text }], details: {} };
    },
  } : undefined;
  const evidence = { mode: hasReadBudget ? 'STAGED' : 'PRELOADED', skillSha256: guidance.skillSha256,
    guideSha256: guidance.guideSha256, preloadedBytes: hasReadBudget ? 0 : skillBytes };
  return { instructions, tool, evidence, continuation: mode === 'received-result' };
}
