// Generated contract version; owned by packaging/generate-public-query.py.
const PUBLIC_TOOL_CONTRACT_VERSION = 3;
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { joinSession } from "@github/copilot-sdk/extension";

const dataHome = process.env.XDG_DATA_HOME || join(homedir(), ".local", "share");
const command = process.env.KAST_TOOL_RPC_COMMAND ||
  join(dataHome, "kast", "current", "bin", "kast-tool-rpc-complete");
if (!existsSync(command)) throw new Error("Kast tool RPC command is not installed");

function run(args, input = "", policy = null, signal = null) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: process.cwd(), stdio: ["pipe", "pipe", "pipe"] });
    const chunks = [];
    let size = 0;
    const maxResponseBytes = policy?.maxResponseBytes ?? 4_194_304;
    const timer = setTimeout(() => child.kill(), policy?.callTimeoutMillis ?? 180_000);
    const abort = () => child.kill();
    signal?.addEventListener("abort", abort, { once: true });
    child.stdout.on("data", (chunk) => {
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
      if (code !== 0 || size > maxResponseBytes) return reject(new Error("Kast command failed"));
      try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8"))); }
      catch { reject(new Error("Kast command returned invalid JSON")); }
    });
    child.stdin.end(input);
  });
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
    ...(tool.effect === "READ" ? { skipPermission: true } : {}),
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
