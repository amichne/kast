import { spawnSync } from 'node:child_process';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { Type } from 'typebox';
import { compileWorkspace } from './developer-build.mjs';
import { createWorkspaceFile, listWorkspaceFiles, readWorkspaceFile, replaceWorkspaceText, searchWorkspace } from './developer-workspace.mjs';
import { permittedKast, runtimeEnvironment } from './skill-sandbox.mjs';

const filePath = Type.String({ minLength: 1, maxLength: 512 });
const rejection = new Set(['PATH_REJECTED', 'WORKSPACE_FILE_LIMIT', 'SEARCH_REJECTED', 'EDIT_REJECTED', 'EDIT_TARGET_NOT_UNIQUE', 'EDIT_TARGET_EXISTS']);

function reply(value: unknown, details: Record<string, unknown> = {}, isError = false) {
  return { content: [{ type: 'text' as const, text: typeof value === 'string' ? value : JSON.stringify(value) }], details, isError };
}

function sourceEffect(operation: () => unknown) {
  try { return reply(operation()); }
  catch (error) {
    const reason = error instanceof Error && rejection.has(error.message) ? error.message : 'WORKSPACE_PATH_UNAVAILABLE';
    return reply({ error: reason }, { reason }, true);
  }
}

export default function (pi: ExtensionAPI) {
  const workspace = process.env.KAST_DEV_EVAL_WORKSPACE!;
  pi.registerTool({
    name: 'list_files', label: 'List project files',
    description: 'List workspace-relative Kotlin and build file paths.',
    parameters: Type.Object({}, { additionalProperties: false }),
    async execute() { return sourceEffect(() => listWorkspaceFiles(workspace)); },
  });
  pi.registerTool({
    name: 'read_file', label: 'Read project file',
    description: 'Read one workspace-relative Kotlin or build file.',
    parameters: Type.Object({ path: filePath }, { additionalProperties: false }),
    async execute(_id, { path }) { return sourceEffect(() => readWorkspaceFile(workspace, path)); },
  });
  pi.registerTool({
    name: 'search_source', label: 'Find source text',
    description: 'Search for literal text in workspace Kotlin and build files. Return workspace-relative paths and line numbers.',
    parameters: Type.Object({ text: Type.String({ minLength: 1, maxLength: 200 }) }, { additionalProperties: false }),
    async execute(_id, { text }) { return sourceEffect(() => searchWorkspace(workspace, text)); },
  });
  pi.registerTool({
    name: 'replace_text', label: 'Replace Kotlin source text',
    description: 'Replace one exact, unique span in an existing Kotlin file under src/main/kotlin.',
    parameters: Type.Object({ path: filePath, before: Type.String({ minLength: 1, maxLength: 16000 }),
      after: Type.String({ maxLength: 16000 }) }, { additionalProperties: false }),
    async execute(_id, { path, before, after }) {
      return sourceEffect(() => replaceWorkspaceText(workspace, path, before, after));
    },
  });
  pi.registerTool({
    name: 'create_file', label: 'Create Kotlin source file',
    description: 'Create a new .kt file in an existing directory under src/main/kotlin. Existing files stay unchanged.',
    parameters: Type.Object({ path: filePath, text: Type.String({ maxLength: 16000 }) }, { additionalProperties: false }),
    async execute(_id, { path, text }) { return sourceEffect(() => createWorkspaceFile(workspace, path, text)); },
  });
  pi.registerTool({
    name: 'compile_project', label: 'Compile project',
    description: 'Compile the current Kotlin production files. Return compiler errors.',
    parameters: Type.Object({}, { additionalProperties: false }),
    async execute() {
      const outcome = compileWorkspace(workspace, process.env.KAST_DEV_EVAL_KOTLINC!);
      return reply(outcome, { reason: outcome.type }, outcome.type !== 'COMPILED');
    },
  });

  const kast = process.env.KAST_DEV_EVAL_KAST;
  if (!kast) return;
  pi.registerTool({
    name: 'kast', label: 'Kast semantic query',
    description: 'Run read-only Kast CLI commands with argv after kast. Start with argv ["query","--help"]. Use --json for structured output. Use stdin with an admitted query request document.',
    parameters: Type.Object({ argv: Type.Array(Type.String({ maxLength: 4096 }), { minItems: 1, maxItems: 32 }),
      stdin: Type.Optional(Type.String({ maxLength: 8192 })) }, { additionalProperties: false }),
    async execute(_id, { argv, stdin }) {
      if (!permittedKast(argv, 'repository', stdin)) return reply({ error: 'KAST_COMMAND_REJECTED' }, { reason: 'KAST_COMMAND_REJECTED' }, true);
      const result = spawnSync(kast, argv, { cwd: workspace, env: runtimeEnvironment(), encoding: 'utf8',
        timeout: 60_000, maxBuffer: 256 * 1024, killSignal: 'SIGKILL', input: stdin });
      if (result.error?.code === 'ETIMEDOUT') return reply({ error: 'KAST_TIME_LIMIT' }, { reason: 'KAST_TIME_LIMIT' }, true);
      if (result.error?.code === 'ENOBUFS') return reply({ error: 'KAST_OUTPUT_LIMIT' }, { reason: 'KAST_OUTPUT_LIMIT' }, true);
      if (result.error || result.status === null) return reply({ error: 'KAST_EFFECT_UNAVAILABLE' }, { reason: 'KAST_EFFECT_UNAVAILABLE' }, true);
      let complete = false;
      try {
        const response = JSON.parse(result.stdout);
        const document = response.document;
        complete = result.status === 0 && response.type === 'complete' && document?.status === 'complete' &&
          document.coverage?.exhaustive === true && document.original_evidence_summary?.failure_count === 0 &&
          document.original_evidence_summary?.omission_count === 0;
      } catch {}
      const text = result.stdout.replaceAll(`${workspace}/`, '') || 'Kast did not return query output.';
      return reply(text, { status: result.status, complete }, result.status !== 0);
    },
  });
}
