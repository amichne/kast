// Generated contract version; owned by packaging/generate-public-query.py.
const PUBLIC_TOOL_CONTRACT_VERSION = 11;
// Generated from ToolRpcFailure; owned by packaging/generate-public-query.py.
const TOOL_RPC_FAILURES = [
  "INSTALLATION_STOPPED",
  "OBSERVATION_UNAVAILABLE",
  "CATALOG_UNAVAILABLE",
  "INVALID_COMMAND",
  "REQUEST_TOO_LARGE",
  "UNKNOWN_TOOL",
  "INVALID_ARGUMENTS",
  "OUT_OF_SCOPE",
  "INVOCATION_FAILED",
  "INVALID_RESULT",
];
// End generated ToolRpcFailure.
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { joinSession } from "@github/copilot-sdk/extension";

const installRoot = join(homedir(), ".local", "share", "kast");
const command = process.env.KAST_TOOL_RPC_COMMAND ||
  join(installRoot, "installation", "bin", "kast-tool-rpc-complete");
if (!existsSync(command)) throw new Error("Kast tool RPC command is not installed");

function run(args, input = "", policy = null, signal = null) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new Error("Kast invocation CANCELLED before launch"));
    const childEnvironment = { ...process.env };
    delete childEnvironment.KAST_TOOL_RPC_COMMAND;
    const child = spawn(command, args, { cwd: process.cwd(), env: childEnvironment, stdio: ["pipe", "pipe", "pipe"] });
    const chunks = [];
    let size = 0;
    let stderrBytes = 0;
    let phaseEvidence = "";
    let stderrLine = "";
    let termination = null;
    const started = Date.now();
    const maxResponseBytes = policy?.maxResponseBytes ?? 4_194_304;
    const deadline = policy?.callTimeoutMillis ?? 180_000;
    const stop = (reason) => { termination ??= reason; child.kill("SIGKILL"); };
    const timer = setTimeout(() => stop("TIMEOUT"), deadline);
    const abort = () => stop("CANCELLED");
    const cleanup = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
    };
    const failure = (reason, exitCode = null) => new Error(
      `Kast subprocess stage=${args[0]} outcome=${reason} exitCode=${exitCode ?? "unknown"} elapsedMs=${Date.now() - started} deadlineMs=${deadline} ` +
      `executable=${command} stderrBytes=${stderrBytes} ${phaseEvidence}; mutation state is unknown; do not replay a write`,
    );
    signal?.addEventListener("abort", abort, { once: true });
    child.stdout.on("data", (chunk) => {
      size += chunk.length;
      if (size > maxResponseBytes) stop("OUTPUT_OVERFLOW");
      else chunks.push(chunk);
    });
    child.stderr.on("data", (chunk) => {
      stderrBytes += chunk.length;
      // Keep only closed diagnostic fields, never source text, arbitrary stderr or reference tokens.
      stderrLine = (stderrLine + chunk.toString("utf8")).slice(-4096);
      const lines = stderrLine.split("\n");
      stderrLine = lines.pop() ?? "";
      for (const line of lines) {
        const phase = line.match(/kast_change stage=([A-Z_]{1,48}) outcome=([A-Z_]{1,32})(?: plan=(plan:[0-9a-f]{64}))?/);
        if (phase) phaseEvidence = `phase=${phase[1]} phaseOutcome=${phase[2]}${phase[3] ? ` plan=${phase[3]}` : ""}`;
      }
    });
    child.on("error", () => { cleanup(); reject(failure("LAUNCH_FAILED")); });
    child.stdin.on("error", () => stop("INPUT_FAILED"));
    child.on("close", (code, exitSignal) => {
      cleanup();
      if (termination) return reject(failure(termination));
      if (code !== 0) return reject(failure(exitSignal ? "SIGNALLED" : "NONZERO_EXIT", code));
      let decoded;
      try { decoded = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)); }
      catch { return reject(failure("INVALID_UTF8")); }
      let result;
      try { result = JSON.parse(decoded); }
      catch { return reject(failure("INVALID_JSON")); }
      if (args[0] === "call" && !isToolResult(result)) return reject(failure("INVALID_RESULT"));
      resolve(result);
    });
    child.stdin.end(input);
  });
}

function isToolResult(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== 2) return false;
  if (value.type === "rejected") return TOOL_RPC_FAILURES.includes(value.failure);
  // Canonical document is opaque here; the Kotlin RPC validates its owning schema.
  return ["complete", "qualified", "rejected_document"].includes(value.type) &&
    value.document !== null && typeof value.document === "object" && !Array.isArray(value.document);
}

const reply = await run(["catalog"]);
if (reply?.type !== "catalog" || reply.catalog?.schemaVersion !== PUBLIC_TOOL_CONTRACT_VERSION ||
    !Number.isSafeInteger(reply.catalog.callTimeoutMillis) || reply.catalog.callTimeoutMillis < 1 ||
    !Number.isSafeInteger(reply.catalog.maxResponseBytes) || reply.catalog.maxResponseBytes < 1 ||
    !Array.isArray(reply.catalog.tools)) throw new Error(`Kast catalog admission failed: expected=${PUBLIC_TOOL_CONTRACT_VERSION} observed=${Number.isSafeInteger(reply?.catalog?.schemaVersion) ? reply.catalog.schemaVersion : "invalid"} executable=${command}`);
const policy = reply.catalog;
const names = new Set();
const tools = reply.catalog.tools.map((tool) => {
  if (tool == null || typeof tool.name !== "string" || !/^[a-z][a-z0-9_]{0,63}$/.test(tool.name) || names.has(tool.name) ||
      typeof tool.description !== "string" || !tool.description.trim() ||
      tool.inputSchema == null || typeof tool.inputSchema !== "object" || Array.isArray(tool.inputSchema) ||
      tool.inputSchema.type !== "object" ||
      !["READ", "WRITE"].includes(tool.effect)) throw new Error(`Kast catalog entry admission failed: executable=${command}`);
  names.add(tool.name);
  return {
    name: tool.name,
    description: tool.description,
    parameters: tool.inputSchema,
    handler: async (args, context) => {
      const result = await run(["call", tool.name], JSON.stringify(args ?? {}), policy, context?.signal);
      return {
        textResultForLlm: JSON.stringify(result),
        resultType: ["complete", "qualified"].includes(result.type) ? "success" : "failure",
      };
    },
  };
});

await joinSession({ tools });
