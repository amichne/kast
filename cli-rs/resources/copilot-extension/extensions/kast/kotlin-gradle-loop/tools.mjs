import {execFile} from "node:child_process";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SCRIPT_DIR = join(HERE, "scripts");

function execProcess(file, args) {
  return new Promise((res) => {
    execFile(file, args, { maxBuffer: 32 * 1024 * 1024 }, (err, stdout, stderr) => {
      res({ ok: !err, code: err?.code ?? 0, stdout: String(stdout ?? ""), stderr: String(stderr ?? "") });
    });
  });
}

async function runJsonProcess(label, file, args) {
  const { stdout, stderr, code } = await execProcess(file, args);
  const out = stdout.trim();
  if (!out) {
    return JSON.stringify({ ok: false, stage: "extension.exec", command: label, message: `${label} produced no JSON output (exit ${code})`, errorText: stderr.trim() || null });
  }
  try {
    JSON.parse(out);
    return out;
  } catch {
    return JSON.stringify({ ok: false, stage: "extension.parse", command: label, message: `${label} returned non-JSON (exit ${code})`, raw: out, errorText: stderr.trim() || null });
  }
}

function scriptPath(...segments) {
  return join(SCRIPT_DIR, ...segments);
}

function arrayArgs(args) {
  return Array.isArray(args) ? args.map(String) : [];
}

async function initializeState(projectRoot) {
  const result = await runJsonProcess("gradle_init_state", "python3", [
    scriptPath("state", "init_state.py"),
    projectRoot,
  ]);
  try {
    return { result, parsed: JSON.parse(result) };
  } catch {
    return { result, parsed: { ok: false } };
  }
}

export function makeKotlinGradleLoopTools() {
  return [
    {
      name: "gradle_run_task",
      description: "Run a Gradle task through kotlin-gradle-loop scripts and return structured JSON output.",
      parameters: {
        type: "object",
        properties: {
          projectRoot: { type: "string", description: "Absolute project root path." },
          task: { type: "string", description: "Gradle task (example: test, :module:test)." },
          args: { type: "array", items: { type: "string" }, description: "Optional extra Gradle args." },
        },
        required: ["projectRoot", "task"],
      },
      handler: (args) => {
        return runJsonProcess("gradle_run_task", "bash", [
          scriptPath("gradle", "run_task.sh"),
          args.projectRoot,
          args.task,
          ...arrayArgs(args.args),
        ]);
      },
    },
    {
      name: "gradle_init_state",
      description: "Initialize or migrate kotlin-gradle-loop state for a Gradle project.",
      parameters: {
        type: "object",
        properties: {
          projectRoot: { type: "string", description: "Absolute project root path." },
        },
        required: ["projectRoot"],
      },
      handler: (args) =>
        runJsonProcess("gradle_init_state", "python3", [
          scriptPath("state", "init_state.py"),
          args.projectRoot,
        ]),
    },
    {
      name: "gradle_set_hook",
      description: "Set project.gradleHook in kotlin-gradle-loop state. Use a narrow task like :module:test when possible.",
      parameters: {
        type: "object",
        properties: {
          projectRoot: { type: "string", description: "Absolute project root path." },
          task: { type: "string", description: "Gradle task to run for gradle_run_hook." },
        },
        required: ["projectRoot", "task"],
      },
      handler: async (args) => {
        const init = await initializeState(args.projectRoot);
        if (!init.parsed.ok) return init.result;
        return runJsonProcess("gradle_set_hook", "python3", [
          scriptPath("state", "update_state.py"),
          args.projectRoot,
          "project.gradleHook",
          JSON.stringify(String(args.task)),
        ]);
      },
    },
    {
      name: "gradle_get_state",
      description: "Read kotlin-gradle-loop state, a state path, a summary, or recent history.",
      parameters: {
        type: "object",
        properties: {
          projectRoot: { type: "string", description: "Absolute project root path." },
          path: { type: "string", description: "Optional dotpath like project.gradleHook or tests.failures." },
          summary: { type: "boolean", description: "Return a compact status summary." },
          historyLimit: { type: "integer", description: "Return the most recent N history entries." },
        },
        required: ["projectRoot"],
      },
      handler: (args) => {
        const scriptArgs = [scriptPath("state", "get_state.py"), args.projectRoot];
        if (args.summary === true) {
          scriptArgs.push("--summary");
        } else if (Number.isInteger(args.historyLimit)) {
          scriptArgs.push("--history", String(args.historyLimit));
        } else if (args.path) {
          scriptArgs.push(String(args.path));
        }
        return runJsonProcess("gradle_get_state", "python3", scriptArgs);
      },
    },
    {
      name: "gradle_run_hook",
      description: "Run project.gradleHook via kotlin-gradle-loop and return structured JSON. If unset, call gradle_set_hook first.",
      parameters: {
        type: "object",
        properties: {
          projectRoot: { type: "string" },
          args: { type: "array", items: { type: "string" }, description: "Optional extra Gradle args." },
        },
        required: ["projectRoot"],
      },
      handler: (args) =>
        runJsonProcess("gradle_run_hook", "bash", [
          scriptPath("gradle", "run_gradle_hook.sh"),
          args.projectRoot,
          ...arrayArgs(args.args),
        ]),
    },
    {
      name: "gradle_parse_junit",
      description: "Parse JUnit XML reports via kotlin-gradle-loop and return normalized JSON.",
      parameters: {
        type: "object",
        properties: {
          projectRoot: { type: "string" },
          module: { type: "string", description: "Optional module selector like :feature-users." },
        },
        required: ["projectRoot"],
      },
      handler: (args) => {
        const scriptArgs = [scriptPath("parse", "junit_results.py"), args.projectRoot];
        if (args.module) scriptArgs.push("--module", String(args.module));
        return runJsonProcess("gradle_parse_junit", "python3", scriptArgs);
      },
    },
  ];
}
