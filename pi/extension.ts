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

const dataHome = process.env.XDG_DATA_HOME || join(homedir(), ".local", "share");
const command = process.env.KAST_TOOL_RPC_COMMAND ||
  join(dataHome, "kast", "current", "bin", "kast-tool-rpc-complete");

function run(args: string[], input: string, cwd: string, policy?: {callTimeoutMillis: number; maxResponseBytes: number}, signal?: AbortSignal): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: ["pipe", "pipe", "pipe"] });
    const chunks: Buffer[] = [];
    let size = 0;
    const maxResponseBytes = policy?.maxResponseBytes ?? 4_194_304;
    const timer = setTimeout(() => child.kill(), policy?.callTimeoutMillis ?? 180_000);
    const abort = () => child.kill();
    signal?.addEventListener("abort", abort, { once: true });
    child.stdout.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > maxResponseBytes) child.kill();
      else chunks.push(chunk);
    });
    child.stderr.resume();
    child.on("error", reject);
    child.on("close", (code) => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
      if (signal?.aborted) return reject(new Error("Kast invocation cancelled; mutation state is unknown"));
      if (code !== 0 || size > maxResponseBytes) return reject(new Error("Kast tool RPC failed"));
      try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8"))); }
      catch { reject(new Error("Kast tool RPC returned invalid JSON")); }
    });
    child.stdin.end(input);
  });
}

export default async function (pi: ExtensionAPI) {
  if (!existsSync(command)) throw new Error("Kast tool RPC command is not installed");
  const reply = await run(["catalog"], "", process.cwd()) as {
    type?: string;
    catalog?: { schemaVersion?: number; callTimeoutMillis?: number; maxResponseBytes?: number; tools?: Tool[] };
  };
  if (reply.type !== "catalog" || reply.catalog?.schemaVersion !== 2 ||
      !Number.isSafeInteger(reply.catalog.callTimeoutMillis) || !Number.isSafeInteger(reply.catalog.maxResponseBytes) ||
      (reply.catalog.callTimeoutMillis ?? 0) < 1 || (reply.catalog.maxResponseBytes ?? 0) < 1 ||
      !Array.isArray(reply.catalog.tools)) throw new Error("Invalid Kast tool catalog");
  const policy = reply.catalog as {callTimeoutMillis: number; maxResponseBytes: number; tools: Tool[]};
  const names = new Set<string>();
  for (const tool of reply.catalog.tools) {
    if (typeof tool.name !== "string" || names.has(tool.name) ||
        !["READ", "WRITE"].includes(tool.effect)) throw new Error("Invalid Kast tool entry");
    names.add(tool.name);
    pi.registerTool({
      name: tool.name,
      label: `Kast ${tool.name}`,
      description: tool.description,
      parameters: Type.Unsafe(tool.inputSchema),
      async execute(_id, params, signal, _onUpdate, ctx) {
        if (tool.effect === "WRITE" &&
            (!ctx.hasUI || !await ctx.ui.confirm("Apply Kast change?", tool.description))) {
          return { content: [{ type: "text", text: "Kast change was not approved." }] };
        }
        const result = await run(["call", tool.name], JSON.stringify(params), ctx.cwd, policy, signal);
        return { content: [{ type: "text", text: JSON.stringify(result) }] };
      },
    });
  }
}
