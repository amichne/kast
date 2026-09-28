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
    const timer = policy && setTimeout(() => child.kill(), policy.callTimeoutMillis);
    const abort = () => child.kill();
    signal?.addEventListener("abort", abort, { once: true });
    child.stdout.on("data", (chunk) => {
      size += chunk.length;
      if (policy && size > policy.maxResponseBytes) child.kill();
      else chunks.push(chunk);
    });
    child.stderr.resume();
    child.on("error", reject);
    child.on("close", (code) => {
      if (timer) clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
      if (signal?.aborted) return reject(new Error("Kast invocation cancelled; mutation state is unknown"));
      if (code !== 0 || (policy && size > policy.maxResponseBytes)) return reject(new Error("Kast command failed"));
      try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8"))); }
      catch { reject(new Error("Kast command returned invalid JSON")); }
    });
    child.stdin.end(input);
  });
}

const reply = await run(["catalog"]);
if (reply.type !== "catalog" || reply.catalog?.schemaVersion !== 2 ||
    !Number.isSafeInteger(reply.catalog.callTimeoutMillis) || reply.catalog.callTimeoutMillis < 1 ||
    !Number.isSafeInteger(reply.catalog.maxResponseBytes) || reply.catalog.maxResponseBytes < 1 ||
    !Array.isArray(reply.catalog.tools)) throw new Error("Invalid Kast tool catalog");
const policy = reply.catalog;
const names = new Set();
const tools = reply.catalog.tools.map((tool) => {
  if (typeof tool.name !== "string" || names.has(tool.name) ||
      !["READ", "WRITE"].includes(tool.effect)) throw new Error("Invalid Kast tool entry");
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
