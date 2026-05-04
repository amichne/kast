import { execFile } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { joinSession } from "@github/copilot-sdk/extension";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, "..", "..", "..");
const SKILL_DIR = join(REPO_ROOT, ".agents", "skills", "kotlin-gradle-loop");

function execBash(command) {
  return new Promise((res) => {
    execFile("bash", ["-lc", command], { maxBuffer: 32 * 1024 * 1024 }, (err, stdout, stderr) => {
      res({ ok: !err, code: err?.code ?? 0, stdout: String(stdout ?? ""), stderr: String(stderr ?? "") });
    });
  });
}

async function runJsonCommand(label, command) {
  const { stdout, stderr, code } = await execBash(command);
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

function shellArg(value) {
  return JSON.stringify(String(value));
}

const tools = [
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
      const extra = (args.args ?? []).map(shellArg).join(" ");
      const cmd = `bash ${shellArg(join(SKILL_DIR, "scripts", "gradle", "run_task.sh"))} ${shellArg(args.projectRoot)} ${shellArg(args.task)}${extra ? ` ${extra}` : ""}`;
      return runJsonCommand("gradle_run_task", cmd);
    },
  },
  {
    name: "gradle_run_hook",
    description: "Run project.gradleHook via kotlin-gradle-loop and return structured JSON.",
    parameters: { type: "object", properties: { projectRoot: { type: "string" } }, required: ["projectRoot"] },
    handler: (args) =>
      runJsonCommand("gradle_run_hook", `bash ${shellArg(join(SKILL_DIR, "scripts", "gradle", "run_gradle_hook.sh"))} ${shellArg(args.projectRoot)}`),
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
      const mod = args.module ? ` --module ${shellArg(args.module)}` : "";
      return runJsonCommand("gradle_parse_junit", `python3 ${shellArg(join(SKILL_DIR, "scripts", "parse", "junit_results.py"))} ${shellArg(args.projectRoot)}${mod}`);
    },
  },
];

const session = await joinSession({ tools });
await session.log("gradle-suite extension ready", { ephemeral: true });
