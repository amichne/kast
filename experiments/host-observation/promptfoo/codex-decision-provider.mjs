import { rmSync } from 'node:fs';
import { comprehensionSystem } from './comprehension-cases.mjs';
import { createParticipantDirectory } from './participant-surface.mjs';

export function decisionOnly(response) {
  const turn = response?.metadata?.codexAppServer;
  return turn?.turnId && turn.itemCounts?.agentMessage > 0 &&
    Object.keys(turn.itemCounts).every(type => ['userMessage', 'reasoning', 'agentMessage'].includes(type));
}

export async function codexDecisionProvider() {
  const workspace = await createParticipantDirectory();
  process.once('exit', () => rmSync(workspace, { recursive: true, force: true }));
  return {
    id: 'openai:codex-app-server:gpt-6.1-sol',
    config: {
      working_dir: workspace, skip_git_repo_check: true, model_reasoning_effort: 'low', model_provider: 'openai',
      sandbox_mode: 'read-only', approval_policy: 'never', ephemeral: true, persist_threads: false,
      network_access_enabled: false, turn_timeout_ms: 120000,
      base_instructions: comprehensionSystem + ' Use only the evidence in the request. Do not use tools.',
      cli_config: { features: { shell_tool: false, apps: false, memories: false }, web_search: 'disabled' },
    },
  };
}
