// Preferred Copilot SDK adapter. The bundled task service owns schemas and execution.
import { spawn } from "node:child_process";
import { homedir } from "node:os";
import { join } from "node:path";
import { joinSession } from "@github/copilot-sdk/extension";

const script = join(homedir(), ".local/share/kast/installation/share/kast/agent-tools/plugins/kast/skills/kast/scripts/server.py");

function run(args, input = "", signal) {
  return new Promise((resolve, reject) => {
    const controller = new AbortController();
    // Covers every admitted policy, including an installation change after discovery.
    // The service applies the current catalog deadline to each RPC invocation.
    const deadline = args[0] === "call" ? 3_780_000 : 180_000;
    const signals = [controller.signal, AbortSignal.timeout(deadline)];
    // JSON string escaping can expand one RPC byte to six bytes. The bridge omits structuredContent.
    const maxBytes = args[0] === "call" ? 6 * 2_147_483_647 + 1024 : 1_048_576;
    if (signal) signals.push(signal);
    const child = spawn("uv", ["run", "--locked", "--script", script, ...args], {
      cwd: process.cwd(), stdio: ["pipe", "pipe", "pipe"],
      signal: AbortSignal.any(signals), killSignal: "SIGINT",
    });
    const chunks = [];
    let size = 0;
    let stopped = false;
    const fail = reason => {
      if (stopped) return;
      stopped = true;
      controller.abort();
      reject(new Error(`KAST_TASK_${reason}: submission or effects can be uncertain; do not retry automatically`));
    };
    child.stdout.on("data", chunk => {
      size += chunk.length;
      if (size > maxBytes) return fail("OUTPUT_LIMIT");
      chunks.push(chunk);
    });
    child.stderr.on("data", () => {}); // Drain diagnostics without mixing them with the canonical reply.
    child.on("error", () => fail("PROCESS_UNAVAILABLE"));
    child.stdin.on("error", () => fail("INPUT_FAILED"));
    child.on("close", code => {
      if (stopped) return;
      if (code !== 0) return fail("PROCESS_FAILED");
      try { resolve(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)))); }
      catch { fail("INVALID_REPLY"); }
    });
    child.stdin.end(input);
  });
}

const catalog = await run(["catalog"]);
if (!Array.isArray(catalog?.tools) || catalog.tools.length === 0) throw new Error("KAST_TASK_INVALID_CATALOG");
const names = new Set();
const tools = catalog.tools.map(tool => {
  if (typeof tool.name !== "string" || !/^[a-z][a-z_]{0,63}$/.test(tool.name) || names.has(tool.name) ||
      typeof tool.description !== "string" || tool.inputSchema?.type !== "object") {
    throw new Error("KAST_TASK_INVALID_CATALOG");
  }
  names.add(tool.name);
  return {
    name: tool.name, description: tool.description, parameters: tool.inputSchema,
    handler: async (args, context) => {
      const result = await run(["call", tool.name], JSON.stringify(args ?? {}), context?.signal);
      if (typeof result?.isError !== "boolean" || !Array.isArray(result.content) ||
          !result.content.every(item => item.type === "text" && typeof item.text === "string")) {
        throw new Error("KAST_TASK_INVALID_REPLY");
      }
      return {
        textResultForLlm: result.content.map(item => item.text).join("\n"),
        resultType: result.isError ? "failure" : "success",
      };
    },
  };
});

await joinSession({ tools });
