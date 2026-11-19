import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { listWorkspaceFiles } from './developer-workspace.mjs';
import { runtimeEnvironment } from './skill-sandbox.mjs';

export function compileWorkspace(workspace, compiler) {
  const sources = listWorkspaceFiles(workspace).filter(path => path.startsWith('src/main/kotlin/') && path.endsWith('.kt'));
  if (!sources.length) return { type: 'COMPILER_UNAVAILABLE' };
  const directory = mkdtempSync(join(tmpdir(), 'kast-developer-compilation-'));
  try {
    const result = spawnSync(compiler, [...sources, '-d', join(directory, 'classes.jar')], {
      cwd: workspace, env: runtimeEnvironment(), encoding: 'utf8', timeout: 90_000, maxBuffer: 256 * 1024, killSignal: 'SIGKILL',
    });
    if (result.error?.code === 'ETIMEDOUT') return { type: 'COMPILER_TIME_LIMIT' };
    if (result.error?.code === 'ENOBUFS') return { type: 'COMPILER_OUTPUT_LIMIT' };
    if (result.error || result.status === null) return { type: 'COMPILER_UNAVAILABLE' };
    if (result.status === 0) return { type: 'COMPILED' };
    const diagnostics = `${result.stdout}\n${result.stderr}`.replaceAll(`file://${workspace}/`, '').replaceAll(`${workspace}/`, '')
      .split('\n').filter(line => /\.(?:kt|kts):\d+:\d+:/.test(line)).join('\n').slice(0, 6000);
    return diagnostics ? { type: 'COMPILATION_FAILED', diagnostics } : { type: 'COMPILER_UNAVAILABLE' };
  } finally {
    rmSync(directory, { force: true, recursive: true });
  }
}
