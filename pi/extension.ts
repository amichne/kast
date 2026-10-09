// Generated contract version; owned by packaging/generate-public-query.py.
const PUBLIC_TOOL_CONTRACT_VERSION = 14;
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
] as const;
// End generated ToolRpcFailure.
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

type Tool = {
  name: string;
  description: string;
  effect: "READ" | "WRITE";
  inputSchema: Record<string, unknown>;
};

const installRoot = join(homedir(), ".local", "share", "kast");
const command = process.env.KAST_TOOL_RPC_COMMAND ||
  join(installRoot, "installation", "bin", "kast-tool-rpc-complete");

function run(args: string[], input: string, cwd: string, policy?: {callTimeoutMillis: number; maxResponseBytes: number}, signal?: AbortSignal): Promise<unknown> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new Error("Kast invocation CANCELLED before launch"));
    const childEnvironment = { ...process.env };
    delete childEnvironment.KAST_TOOL_RPC_COMMAND;
    const child = spawn(command, args, { cwd: cwd, env: childEnvironment, stdio: ["pipe", "pipe", "pipe"] });
    const chunks : Buffer[] = [];
    let size = 0;
    let stderrBytes = 0;
    let phaseEvidence = "";
    let stderrLine = "";
    let termination : "TIMEOUT" | "CANCELLED" | "OUTPUT_OVERFLOW" | "INPUT_FAILED" | null = null;
    const started = Date.now();
    const maxResponseBytes = policy?.maxResponseBytes ?? 4_194_304;
    const deadline = policy?.callTimeoutMillis ?? 180_000;
    const stop = (reason: NonNullable<typeof termination>) => { termination ??= reason; child.kill("SIGKILL"); };
    const timer = setTimeout(() => stop("TIMEOUT"), deadline);
    const abort = () => stop("CANCELLED");
    const cleanup = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
    };
    const failure = (reason: string, exitCode: number | null = null) => new Error(
      `Kast subprocess stage=${args[0]} outcome=${reason} exitCode=${exitCode ?? "unknown"} elapsedMs=${Date.now() - started} deadlineMs=${deadline} ` +
      `executable=${command} stderrBytes=${stderrBytes} ${phaseEvidence}; mutation state is unknown; do not replay a write`,
    );
    signal?.addEventListener("abort", abort, { once: true });
    child.stdout.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > maxResponseBytes) stop("OUTPUT_OVERFLOW");
      else chunks.push(chunk);
    });
    child.stderr.on("data", (chunk: Buffer) => {
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
    child.on("close", (code: number | null, exitSignal: string | null) => {
      cleanup();
      if (termination) return reject(failure(termination));
      if (code !== 0) return reject(failure(exitSignal ? "SIGNALLED" : "NONZERO_EXIT", code));
      let decoded: string;
      try { decoded = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)); }
      catch { return reject(failure("INVALID_UTF8")); }
      let result: unknown;
      try { result = JSON.parse(decoded); }
      catch { return reject(failure("INVALID_JSON")); }
      if (args[0] === "call" && !isToolResult(result)) return reject(failure("INVALID_RESULT"));
      resolve(result);
    });
    child.stdin.end(input);
  });
}

type ToolRpcResult =
  | { type: "complete" | "qualified" | "rejected_document"; document: Record<string, unknown> }
  | { type: "rejected"; failure: (typeof TOOL_RPC_FAILURES)[number] };

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isToolResult(value: unknown): value is ToolRpcResult {
  if (!isObject(value) || Object.keys(value).length !== 2) return false;
  if (value.type === "rejected") return typeof value.failure === "string" &&
    (TOOL_RPC_FAILURES as readonly string[]).includes(value.failure);
  // Canonical document is opaque here; the Kotlin RPC validates its owning schema.
  return typeof value.type === "string" &&
    ["complete", "qualified", "rejected_document"].includes(value.type) && isObject(value.document);
}

function approvalSummary(tool: Tool, params: Record<string, unknown>, cwd: string): string {
  const bounded = (value: string, limit: number) => value.length <= limit ? value :
    value.slice(0, limit) + " [truncated; review full tool arguments]";
  const target = typeof params.exactTarget === "string" ? params.exactTarget : "[missing exact target]";
  return `Tool: ${tool.name}\nWorkspace: ${bounded(JSON.stringify(cwd), 384)}\n` +
    `Target: ${bounded(JSON.stringify(target), 256)}\nIntent: ${bounded(JSON.stringify(params), 1200)}`;
}

export default async function (pi: ExtensionAPI) {
  if (!existsSync(command)) throw new Error("Kast tool RPC command is not installed");
  const reply = await run(["catalog"], "", process.cwd()) as {
    type?: string;
    catalog?: { schemaVersion?: number; callTimeoutMillis?: number; maxResponseBytes?: number; tools?: Tool[] };
  };
  if (reply?.type !== "catalog" || reply.catalog?.schemaVersion !== PUBLIC_TOOL_CONTRACT_VERSION ||
      !Number.isSafeInteger(reply.catalog.callTimeoutMillis) || !Number.isSafeInteger(reply.catalog.maxResponseBytes) ||
      (reply.catalog.callTimeoutMillis ?? 0) < 1 || (reply.catalog.maxResponseBytes ?? 0) < 1 ||
      !Array.isArray(reply.catalog.tools)) throw new Error(`Kast catalog admission failed: expected=${PUBLIC_TOOL_CONTRACT_VERSION} observed=${Number.isSafeInteger(reply?.catalog?.schemaVersion) ? reply.catalog.schemaVersion : "invalid"} executable=${command}`);
  const policy = reply.catalog as {callTimeoutMillis: number; maxResponseBytes: number; tools: Tool[]};
  const names = new Set<string>();
  for (const tool of reply.catalog.tools) {
    if (tool == null || typeof tool.name !== "string" || !/^[a-z][a-z0-9_]{0,63}$/.test(tool.name) || names.has(tool.name) ||
      typeof tool.description !== "string" || !tool.description.trim() ||
      tool.inputSchema == null || typeof tool.inputSchema !== "object" || Array.isArray(tool.inputSchema) ||
      tool.inputSchema.type !== "object" ||
        !["READ", "WRITE"].includes(tool.effect)) throw new Error(`Kast catalog entry admission failed: executable=${command}`);
    names.add(tool.name);
  }
  for (const tool of policy.tools) {
    pi.registerTool({
      name: tool.name,
      label: `Kast ${tool.name}`,
      description: tool.description,
      parameters: Type.Unsafe<Record<string, unknown>>(tool.inputSchema),
      async execute(_id, params, signal, _onUpdate, ctx) {
        if (tool.effect === "WRITE" &&
            (!ctx.hasUI || !await ctx.ui.confirm("Apply Kast change?", approvalSummary(tool, params, ctx.cwd), { signal }))) {
          const denied = { type: "APPROVAL_DENIED" } as const;
          return { content: [{ type: "text", text: JSON.stringify(denied) }], details: denied, isError: true };
        }
        const result = await run(["call", tool.name], JSON.stringify(params), ctx.cwd, policy, signal);
        if (!isToolResult(result)) throw new Error("Kast reply admission outcome=INVALID_RESULT");
        return {
          content: [{ type: "text", text: JSON.stringify(result) }],
          details: result,
          isError: ["rejected", "rejected_document"].includes(result.type),
        };
      },
    });
  }
}
