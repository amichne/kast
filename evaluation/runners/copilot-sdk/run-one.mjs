#!/usr/bin/env node
import { execFile } from "node:child_process";
import {
  createWriteStream,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { CopilotClient, approveAll } from "@github/copilot-sdk";
import { KAST_TOOL_NAMES, makeKastTools } from "../../../.github/extensions/_shared/kast-tools.mjs";
import { sha256, summarizeSessionEvents } from "./run-artifacts.mjs";

const execFileAsync = promisify(execFile);
const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, "../../..");
const RUNNER_PACKAGE = JSON.parse(readFileSync(resolve(HERE, "package.json"), "utf8"));
const RUNNER_VERSION = RUNNER_PACKAGE.version;

const CONFIG_POLICIES = {
  with_skill: {
    registerKastTools: true,
    loadKastSkill: true,
    denyDirectKastShell: false,
    baselinePolicy: "not_applicable",
  },
  tool_only: {
    registerKastTools: true,
    loadKastSkill: false,
    denyDirectKastShell: false,
    baselinePolicy: "tool_only",
  },
  without_skill: {
    registerKastTools: false,
    loadKastSkill: false,
    denyDirectKastShell: true,
    baselinePolicy: "deny_direct_kast_shell",
  },
};

function parseArgs(argv) {
  const args = {
    instructions: "",
    transcript: "",
    runDir: "",
    evalId: "",
    configuration: "",
    runNumber: "",
    attempt: "",
  };
  const mapping = {
    "--instructions": "instructions",
    "--transcript": "transcript",
    "--run-dir": "runDir",
    "--eval-id": "evalId",
    "--configuration": "configuration",
    "--run-number": "runNumber",
    "--attempt": "attempt",
  };
  let index = 2;
  while (index < argv.length) {
    const flag = argv[index];
    if (!(flag in mapping)) {
      die(`unknown argument: ${flag}`);
    }
    args[mapping[flag]] = argv[index + 1] ?? "";
    index += 2;
  }
  for (const required of ["instructions", "transcript", "runDir"]) {
    if (!args[required]) {
      die(`--${required.replace(/[A-Z]/g, (match) => `-${match.toLowerCase()}`)} is required`);
    }
  }
  if (!CONFIG_POLICIES[args.configuration]) {
    die(`unsupported configuration: ${args.configuration}`);
  }
  return args;
}

function die(message) {
  process.stderr.write(`error: ${message}\n`);
  process.exit(1);
}

function positiveIntFromEnv(name, fallback) {
  const raw = process.env[name] ?? "";
  if (!raw.trim()) return fallback;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    die(`${name} must be a positive integer, got ${JSON.stringify(raw)}`);
  }
  return parsed;
}

function extractPrompt(instructionsPath) {
  const text = readFileSync(instructionsPath, "utf8");
  const match = text.match(/```text\s*\n(?<prompt>[\s\S]*?)\n```/);
  const prompt = (match?.groups?.prompt ?? text).trim();
  if (!prompt) {
    die(`instructions file did not contain a prompt: ${instructionsPath}`);
  }
  return prompt;
}

function readJsonIfExists(path) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return {};
  }
}

function writeJson(path, payload) {
  writeFileSync(path, `${JSON.stringify(payload, null, 2)}\n`);
}

async function execGit(cwd, ...args) {
  const result = await execFileAsync("git", ["-C", cwd, ...args], { timeout: 120000 });
  return result.stdout.trim();
}

async function execGitAllowFailure(cwd, ...args) {
  try {
    return await execGit(cwd, ...args);
  } catch {
    return "";
  }
}

async function gitState(cwd) {
  const sha = await execGitAllowFailure(cwd, "rev-parse", "HEAD");
  const dirtyStatus = await execGitAllowFailure(cwd, "status", "--porcelain");
  const nameStatusText = await execGitAllowFailure(cwd, "diff", "--name-status");
  const patchText = await execGitAllowFailure(cwd, "diff", "--binary");
  const nameStatus = nameStatusText
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  const touchedFiles = nameStatus.map((line) => line.split(/\s+/).at(-1)).filter(Boolean);
  return {
    sha,
    dirty: Boolean(dirtyStatus),
    diff_name_status: nameStatus,
    touched_files: touchedFiles,
    patch_hash: patchText ? sha256(patchText) : null,
  };
}

async function resolveGitHubToken() {
  const explicit =
    process.env.COPILOT_GITHUB_TOKEN ??
    process.env.COPILOT_SDK_GITHUB_TOKEN ??
    process.env.GITHUB_TOKEN ??
    process.env.GH_TOKEN ??
    "";
  return explicit.trim();
}

async function resolveCopilotCliPath() {
  const explicit = process.env.COPILOT_CLI_PATH ?? process.env.COPILOT_BIN ?? "";
  if (explicit.trim()) return explicit.trim();
  try {
    const { stdout } = await execFileAsync("which", ["copilot"], { timeout: 5000 });
    return stdout.trim();
  } catch {
    return "";
  }
}

async function resolveCliVersion(copilotCliPath) {
  const command = copilotCliPath || "copilot";
  try {
    const { stdout, stderr } = await execFileAsync(command, ["--version"], { timeout: 10000 });
    return (stdout || stderr).trim();
  } catch {
    return "";
  }
}

async function createRunWorktree({ targetRoot, targetSha, worktreePath }) {
  await execFileAsync("git", ["-C", targetRoot, "worktree", "remove", "--force", worktreePath], {
    timeout: 120000,
  }).catch(() => {});
  await execFileAsync("git", ["-C", targetRoot, "worktree", "prune"], {
    timeout: 120000,
  }).catch(() => {});
  rmSync(worktreePath, { recursive: true, force: true });
  await execFileAsync("git", ["-C", targetRoot, "worktree", "add", "--force", "--detach", worktreePath, targetSha], {
    timeout: 120000,
  });
  return worktreePath;
}

async function callKastInWorkspace(workspaceRoot, method, params) {
  const kastBin = process.env.KAST_BIN ?? "kast";
  const rpcRequest = JSON.stringify({ jsonrpc: "2.0", method, params, id: 1 });
  const { stdout } = await execFileAsync(kastBin, ["rpc", rpcRequest, `--workspace-root=${workspaceRoot}`], {
    timeout: 120000,
  });
  return stdout;
}

function containsDirectKastShell(commandText) {
  return /\bkast(?:\s|$)/.test(commandText) || /\bkast_[a-z_]+\b/.test(commandText);
}

function buildSessionConfig({
  configuration,
  model,
  reasoningEffort,
  worktreePath,
  policy,
  permissionLog,
}) {
  const skillRoot = resolve(REPO_ROOT, ".agents/skills");
  const instructionRoot = resolve(REPO_ROOT, ".github/instructions");
  const sessionConfig = {
    clientName: "kast-evaluation-runner",
    model,
    reasoningEffort,
    enableConfigDiscovery: false,
    enableSessionTelemetry: true,
    workingDirectory: worktreePath,
    instructionDirectories: [instructionRoot],
    skillDirectories: policy.loadKastSkill ? [skillRoot] : [],
    disabledSkills: policy.loadKastSkill ? [] : ["kast"],
    systemMessage: {
      mode: "append",
      content:
        `Benchmark configuration: ${configuration}. ` +
        (policy.loadKastSkill
          ? "The real Kast skill directory is loaded."
          : "No Kast skill instructions are loaded."),
    },
    onPermissionRequest: (request, invocation) => {
      const fullCommandText = request?.fullCommandText ?? "";
      if (policy.denyDirectKastShell && request?.kind === "shell" && containsDirectKastShell(fullCommandText)) {
        permissionLog.push({ kind: "denied-by-rules", request });
        return { kind: "denied-by-rules" };
      }
      permissionLog.push({ kind: "approved", request });
      return approveAll(request, invocation);
    },
    hooks: {
      onPreToolUse: async (input) => {
        if (
          policy.denyDirectKastShell &&
          input?.toolName === "bash" &&
          containsDirectKastShell(String(input?.toolArgs?.command ?? ""))
        ) {
          return {
            permissionDecision: "deny",
            modifiedArgs: input.toolArgs,
            additionalContext: "The benchmark baseline denies direct kast shell use.",
          };
        }
        return {
          permissionDecision: "allow",
          modifiedArgs: input.toolArgs,
        };
      },
      onPostToolUse: async () => ({}),
      onUserPromptSubmitted: async (input) => ({ modifiedPrompt: input.prompt }),
      onSessionStart: async () => ({ additionalContext: "Benchmark harness active." }),
      onSessionEnd: async () => {},
      onErrorOccurred: async () => ({ errorHandling: "abort" }),
    },
  };
  if (policy.registerKastTools) {
    sessionConfig.tools = makeKastTools((method, params) => callKastInWorkspace(worktreePath, method, params));
  }
  return sessionConfig;
}

async function readSessionSources(session) {
  try {
    const [skills, instructions] = await Promise.all([
      session.rpc.skills.list(),
      session.rpc.instructions.getSources(),
    ]);
    return {
      loaded_skills: Array.isArray(skills?.skills) ? skills.skills : [],
      instruction_sources: Array.isArray(instructions?.sources) ? instructions.sources : [],
    };
  } catch {
    return {
      loaded_skills: [],
      instruction_sources: [],
    };
  }
}

function hashInstructionSources(sources) {
  if (!Array.isArray(sources) || sources.length === 0) return null;
  const stable = sources
    .map((source) => ({
      sourcePath: source.sourcePath ?? "",
      content: source.content ?? "",
      type: source.type ?? "",
      location: source.location ?? "",
    }))
    .sort((left, right) => left.sourcePath.localeCompare(right.sourcePath));
  return sha256(JSON.stringify(stable));
}

function buildInputs({
  args,
  prompt,
  model,
  reasoningEffort,
  policy,
  bindings,
  evalMetadata,
  renderedCatalog,
  loadedSkills,
  instructionSources,
  worktreePath,
  copilotCliVersion,
}) {
  const caseEntry = Array.isArray(renderedCatalog?.cases)
    ? renderedCatalog.cases.find((candidate) => candidate?.id === args.evalId)
    : null;
  const actualSkillPath = resolve(REPO_ROOT, ".agents/skills/kast/SKILL.md");
  const loadedKastSkill = loadedSkills.find((skill) => skill?.name === "kast");
  return {
    $schema: "https://github.com/amichne/kast/evaluation/mechanical.schema.json",
    schema_version: 1,
    runner_version: RUNNER_VERSION,
    sdk_package_version: String(
      RUNNER_PACKAGE.dependencies?.["@github/copilot-sdk"] ?? RUNNER_PACKAGE.devDependencies?.["@github/copilot-sdk"] ?? "",
    ),
    copilot_cli_version: copilotCliVersion,
    node_version: process.version,
    platform: process.platform,
    model,
    reasoning_effort: reasoningEffort,
    configuration: args.configuration,
    configuration_policy: policy.baselinePolicy,
    target_repository: bindings.target_repo ?? "",
    target_git_sha: bindings.git_sha ?? "",
    benchmark_git_sha: evalMetadata.benchmark_git_sha ?? "",
    benchmark_git_branch: evalMetadata.benchmark_git_branch ?? "",
    prompt,
    prompt_hash: sha256(prompt),
    eval_id: args.evalId,
    rendered_case_version: caseEntry?.version ?? renderedCatalog?.version ?? null,
    worktree_path: worktreePath,
    skill_path: policy.loadKastSkill ? actualSkillPath : null,
    skill_hash: policy.loadKastSkill ? sha256(readFileSync(actualSkillPath, "utf8")) : null,
    skill_loaded: Boolean(loadedKastSkill),
    tool_set_hash: sha256(JSON.stringify(Array.from(KAST_TOOL_NAMES).sort())),
    instruction_hash: hashInstructionSources(instructionSources),
    loaded_skill_paths: loadedSkills.map((skill) => skill.path).filter(Boolean),
    instruction_sources: instructionSources.map((source) => source.sourcePath),
  };
}

function buildMechanicalCapture({
  inputs,
  runtimeMetrics,
  preState,
  postState,
  transcriptPath,
  finalAnswer,
  args,
}) {
  const transcriptText = readFileSync(transcriptPath, "utf8");
  return {
    schema_version: 1,
    status: "captured",
    identity: inputs,
    artifacts: {
      inputs: "inputs.json",
      sdk_events: "sdk-events.jsonl",
      otel: "otel.jsonl",
      final_answer: "final-answer.md",
      transcript: "outputs/transcript.md",
      timing: "timing.json",
    },
    expectations: [],
    summary: {
      passed: 0,
      failed: 0,
      total: 0,
      pass_rate: 0.0,
      outcome_passed: 0,
      outcome_total: 0,
      outcome_pass_rate: 0.0,
      process_pass_rate: 0.0,
      skipped: 0,
    },
    execution_metrics: {
      tool_calls: Object.fromEntries(
        runtimeMetrics.timing.per_tool.reduce((counts, entry) => {
          counts.set(entry.tool_name, (counts.get(entry.tool_name) ?? 0) + 1);
          return counts;
        }, new Map()),
      ),
      tool_call_log: "outputs/tool_calls.jsonl",
      total_tool_calls: runtimeMetrics.tools.total_tool_calls,
      total_steps: runtimeMetrics.tools.total_tool_calls,
      errors_encountered: runtimeMetrics.errors.total_session_errors,
      output_chars: finalAnswer.length,
      transcript_chars: transcriptText.length,
      kast_calls: runtimeMetrics.tools.kast_tool_calls,
      grep_or_find_calls: runtimeMetrics.tools.generic_search_calls,
    },
    timing: {
      executor_duration_seconds:
        typeof runtimeMetrics.timing.wall_clock_run_duration_ms === "number"
          ? runtimeMetrics.timing.wall_clock_run_duration_ms / 1000
          : 0.0,
      grader_duration_seconds: 0.0,
      total_duration_seconds:
        typeof runtimeMetrics.timing.wall_clock_run_duration_ms === "number"
          ? runtimeMetrics.timing.wall_clock_run_duration_ms / 1000
          : 0.0,
      executor_duration_source: "self_reported",
      ...runtimeMetrics.timing,
    },
    integrity: {
      contradictions: [],
      baseline_isolation_violation: args.configuration === "without_skill" && runtimeMetrics.tools.kast_tool_calls > 0,
      attempts: Number.parseInt(args.attempt || "1", 10) || 1,
      flaky: false,
      git_sha_pre: preState.sha,
      git_sha_post: postState.sha,
      workspace_dirty_pre: preState.dirty,
      workspace_dirty_post: postState.dirty,
    },
    tokens: runtimeMetrics.tokens,
    tool_metrics: runtimeMetrics.tools,
    permission_metrics: runtimeMetrics.permissions,
    build_test_iterations: runtimeMetrics.build_test_iterations,
    repo_state: {
      worktree_path: inputs.worktree_path,
      pre_run: preState,
      post_run: postState,
      allowed_file_policy: { status: "not_configured" },
      target_edits_present: null,
      unexpected_edits: null,
      cleanup_policy: {
        preserve_requested: process.env.KAST_EVAL_PRESERVE_WORKTREES === "1",
        cleanup_successful_runs_by_default: true,
      },
      worktree_preserved_for_debugging: true,
    },
    final_answer_sha256: sha256(finalAnswer),
  };
}

async function main() {
  const args = parseArgs(process.argv);
  const policy = CONFIG_POLICIES[args.configuration];
  const prompt = extractPrompt(args.instructions);
  const model = process.env.SDK_MODEL ?? "gpt-5-mini";
  const reasoningEffort = process.env.SDK_REASONING_EFFORT ?? "medium";
  const timeoutMs = positiveIntFromEnv("SDK_TIMEOUT_MS", 180000);
  const runDir = resolve(args.runDir);
  const outputsDir = resolve(runDir, "outputs");
  const transcriptPath = resolve(args.transcript);
  const sdkEventsPath = resolve(runDir, "sdk-events.jsonl");
  const otelPath = resolve(runDir, "otel.jsonl");
  const inputsPath = resolve(runDir, "inputs.json");
  const finalAnswerPath = resolve(runDir, "final-answer.md");
  const mechanicalPath = resolve(runDir, "mechanical.json");
  const timingPath = resolve(runDir, "timing.json");
  const iterationDir = resolve(runDir, "../../..");
  const bindings = readJsonIfExists(resolve(iterationDir, "bindings.json"));
  const renderedCatalog = readJsonIfExists(resolve(iterationDir, "rendered-catalog.json"));
  const evalMetadata = readJsonIfExists(resolve(runDir, "../../eval_metadata.json"));
  const targetRoot = String(bindings.workspace_root ?? process.env.KAST_WORKSPACE_ROOT ?? "").trim() || process.cwd();
  const targetSha = String(bindings.git_sha ?? (await execGitAllowFailure(targetRoot, "rev-parse", "HEAD"))).trim();
  const worktreePath = resolve(runDir, "worktree");
  const runStartedAtMs = Date.now();
  mkdirSync(outputsDir, { recursive: true });
  mkdirSync(dirname(sdkEventsPath), { recursive: true });

  await createRunWorktree({ targetRoot, targetSha, worktreePath });
  const preState = await gitState(worktreePath);

  const transcriptStream = createWriteStream(transcriptPath, { flags: "w" });
  const sdkEventsStream = createWriteStream(sdkEventsPath, { flags: "w" });
  const permissionLog = [];
  const recordedEvents = [];
  const seenEventIds = new Set();
  let finalAnswer = "";
  let sessionError = "";

  const recordEvent = (event) => {
    if (event?.id && seenEventIds.has(event.id)) return;
    if (event?.id) {
      seenEventIds.add(event.id);
    }
    recordedEvents.push(event);
    const line = `${JSON.stringify(event)}\n`;
    sdkEventsStream.write(line);
    transcriptStream.write(line);
    if (event?.type === "assistant.message" && typeof event.data?.content === "string") {
      finalAnswer = event.data.content;
    }
    if (event?.type === "session.error") {
      sessionError = event.data?.message ?? JSON.stringify(event.data ?? {});
    }
  };

  const sessionConfig = buildSessionConfig({
    configuration: args.configuration,
    model,
    reasoningEffort,
    worktreePath,
    policy,
    permissionLog,
  });
  sessionConfig.onEvent = recordEvent;

  const githubToken = await resolveGitHubToken();
  if (githubToken) {
    sessionConfig.gitHubToken = githubToken;
  }
  const copilotCliPath = await resolveCopilotCliPath();
  const copilotCliVersion = await resolveCliVersion(copilotCliPath);
  const clientOptions = {
    ...(githubToken ? { gitHubToken: githubToken, useLoggedInUser: false } : { useLoggedInUser: true }),
    ...(copilotCliPath ? { cliPath: copilotCliPath } : {}),
    telemetry: {
      exporterType: "file",
      filePath: otelPath,
      captureContent: true,
    },
  };

  const client = new CopilotClient(clientOptions);
  await client.start();

  try {
    const session = await client.createSession(sessionConfig);
    const stopListening = session.on(recordEvent);
    try {
      const sources = await readSessionSources(session);
      const inputs = buildInputs({
        args,
        prompt,
        model,
        reasoningEffort,
        policy,
        bindings,
        evalMetadata: {
          ...evalMetadata,
          benchmark_git_sha: await execGitAllowFailure(REPO_ROOT, "rev-parse", "HEAD"),
          benchmark_git_branch: await execGitAllowFailure(REPO_ROOT, "rev-parse", "--abbrev-ref", "HEAD"),
        },
        renderedCatalog,
        loadedSkills: sources.loaded_skills,
        instructionSources: sources.instruction_sources,
        worktreePath,
        copilotCliVersion,
      });
      writeJson(inputsPath, inputs);
      const finalMessage = await session.sendAndWait({ prompt }, timeoutMs);
      if (finalMessage?.data?.content) {
        finalAnswer = finalMessage.data.content;
      }
      writeFileSync(finalAnswerPath, finalAnswer ? `${finalAnswer}\n` : "");
      const runtimeMetrics = summarizeSessionEvents({ events: recordedEvents, runStartedAtMs });
      const postState = await gitState(worktreePath);
      const mechanical = buildMechanicalCapture({
        inputs,
        runtimeMetrics,
        preState,
        postState,
        transcriptPath,
        finalAnswer: runtimeMetrics.final_answer || finalAnswer,
        args,
      });
      writeJson(mechanicalPath, mechanical);
      writeJson(timingPath, mechanical.timing);
    } finally {
      stopListening();
      await session.disconnect();
    }
  } finally {
    await client.stop();
    transcriptStream.end();
    sdkEventsStream.end();
    await Promise.all([
      new Promise((resolvePromise) => transcriptStream.once("finish", resolvePromise)),
      new Promise((resolvePromise) => sdkEventsStream.once("finish", resolvePromise)),
    ]);
  }

  if (sessionError) {
    die(
      `SDK session failed: ${sessionError} ` +
        `(eval=${args.evalId} config=${args.configuration} run=${args.runNumber} attempt=${args.attempt})`,
    );
  }
  if (!finalAnswer) {
    die(
      `SDK session produced no assistant message ` +
        `(eval=${args.evalId} config=${args.configuration} run=${args.runNumber} attempt=${args.attempt})`,
    );
  }
}

await main();
