import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { joinSession } from "@github/copilot-sdk/extension";

const dataHome = process.env.XDG_DATA_HOME || join(homedir(), ".local", "share");
const command = process.env.KAST_TOOL_RPC_COMMAND ||
  join(dataHome, "kast", "current", "bin", "kast-tool-rpc-complete");
if (!existsSync(command)) throw new Error("Kast tool RPC command is not installed");

function run(args, input = "") {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: process.cwd(), stdio: ["pipe", "pipe", "pipe"] });
    const chunks = [];
    let size = 0;
    const timer = setTimeout(() => child.kill(), 180_000);
    child.stdout.on("data", (chunk) => {
      size += chunk.length;
      if (size > 4_194_304) child.kill();
      else chunks.push(chunk);
    });
    child.stderr.resume();
    child.on("error", reject);
    child.on("close", (code) => {
      clearTimeout(timer);
      if (code !== 0 || size > 4_194_304) return reject(new Error("Kast command failed"));
      try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8"))); }
      catch { reject(new Error("Kast command returned invalid JSON")); }
    });
    child.stdin.end(input);
  });
}

// Copilot's model-facing schema uses a closed object; Kast still validates the
// exact generated variant schema at the native boundary.
function parametersFor(schema) {
  if (schema?.type === "object") return schema;
  if (!Array.isArray(schema?.anyOf) || schema.anyOf.length === 0)
    throw new Error("Unsupported Kast tool schema");
  const properties = {};
  let required = null;
  for (const variant of schema.anyOf) {
    if (variant?.type !== "object" || !variant.properties)
      throw new Error("Unsupported Kast tool variant");
    required = required === null ? new Set(variant.required ?? []) :
      new Set([...required].filter((name) => (variant.required ?? []).includes(name)));
    for (const [name, property] of Object.entries(variant.properties)) {
      if (!(name in properties)) properties[name] = property;
      else if (JSON.stringify(properties[name]) !== JSON.stringify(property)) {
        const alternatives = properties[name].anyOf ?? [properties[name]];
        if (!alternatives.some((value) => JSON.stringify(value) === JSON.stringify(property)))
          properties[name] = { anyOf: [...alternatives, property] };
      }
    }
  }
  return {
    type: "object",
    properties,
    required: [...required],
    additionalProperties: false,
  };
}

const reply = await run(["catalog"]);
if (reply.type !== "catalog" || reply.catalog?.schemaVersion !== 1 ||
    !Array.isArray(reply.catalog.tools)) throw new Error("Invalid Kast tool catalog");
const names = new Set();
const tools = reply.catalog.tools.map((tool) => {
  if (typeof tool.name !== "string" || names.has(tool.name) ||
      !["READ", "WRITE"].includes(tool.effect)) throw new Error("Invalid Kast tool entry");
  names.add(tool.name);
  return {
    name: tool.name,
    description: tool.description,
    parameters: parametersFor(tool.inputSchema),
    ...(tool.effect === "READ" ? { skipPermission: true } : {}),
    handler: async (args) => {
      const result = await run(["call", tool.name], JSON.stringify(args ?? {}));
      return {
        textResultForLlm: JSON.stringify(result),
        resultType: ["complete", "qualified"].includes(result.type) ? "success" : "failure",
      };
    },
  };
});

await joinSession({ tools });
