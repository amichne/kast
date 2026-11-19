import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const project = fileURLToPath(new URL('../', import.meta.url));

export function pythonScenario(name, script, planVariable) {
  let active = false;

  async function invoke(action, row) {
    const plan = process.env[planVariable];
    if (!plan) {
      throw new Error(`Set ${planVariable} to an evaluation plan JSON file`);
    }
    // No benchmark paths, private source or credentials enter Promptfoo rows.
    const planPath = resolve(plan);
    const arguments_ = ['run', '--locked', '--project', project, 'python', resolve(project, script)];
    const child = spawn('uv', arguments_, { stdio: ['pipe', 'pipe', 'pipe'] });
    const result = await new Promise((resolve, reject) => {
      let stdout = '';
      child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
      child.stderr.resume();
      child.on('error', reject);
      child.stdin.on('error', reject);
      child.on('close', (code, signal) => {
        if (code !== 0) {
          reject(new Error(`${name} ${action} failed (${signal ?? code})`));
        } else {
          try {
            resolve(JSON.parse(stdout));
          } catch {
            reject(new Error(`${name} ${action} returned invalid evidence`));
          }
        }
      });
      child.stdin.end(JSON.stringify({ action, plan: planPath, row }));
    });
    return result;
  }

  async function cases() {
    const rows = await invoke('cases');
    return rows.map((vars) => ({
      description: [vars.caseId, vars.repeat, vars.arm].filter((value) => value !== undefined).join(' '),
      vars,
    }));
  }

  return class Provider {
    static cases = cases;

    static async beforeAll() {
      await invoke('prepare');
    }

    static async afterAll({ results }) {
      const receipt = await invoke('finish');
      const source = receipt?.sourceAtEnd;
      if (source?.status === 'BLOCKED' && !results.some((row) => row.success === false)) {
        throw new Error(`CAMPAIGN_FINAL_${source.reason}`);
      }
    }

    id() {
      return name;
    }

    async callApi(_prompt, context) {
      if (active) return { error: 'CONCURRENT_SCENARIO_ROW' };
      active = true;
      try {
        const { caseId, taskId, cohort, arm, repeat } = context.vars;
        const row = { caseId, taskId, cohort, arm, repeat };
        const { status, reason, assertion, tokenUsage, metadata } = await invoke('trial', row);
        return {
          output: { status, reason: reason ?? assertion },
          ...(status === 'BLOCKED' ? { error: reason } : {}),
          ...(tokenUsage ? { tokenUsage } : {}),
          ...(metadata ? { metadata } : {}),
        };
      } catch (error) {
        return { error: error.message };
      } finally {
        active = false;
      }
    }
  };
}
