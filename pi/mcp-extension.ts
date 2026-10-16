import { closeSync, constants, fstatSync, lstatSync, openSync, readSync, realpathSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { isAbsolute, join } from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const MAX_CONTEXT_BYTES = 8192;
const MAX_RESOURCE_BYTES = 262144;
const RESOURCE_NAMES = ["onboarding.md", "query-schema.json", "query-examples.json"] as const;
type ContextFailure = "INVALID_WORKSPACE" | "RESOURCE_UNAVAILABLE" | "RESOURCE_REJECTED" | "CONTEXT_TOO_LARGE";
type ContextResult = { type: "ready"; text: string } | { type: "rejected"; reason: ContextFailure };

function readContext(installation: string, cwd: string): ContextResult {
  if (!isAbsolute(cwd) || cwd.length > 4096 || /[\x00-\x1f]/.test(cwd)) {
    return { type: "rejected", reason: "INVALID_WORKSPACE" };
  }
  try {
    const workspace = realpathSync(cwd);
    if (!statSync(workspace).isDirectory()) return { type: "rejected", reason: "INVALID_WORKSPACE" };
    const plugin = realpathSync(join(installation, "share/kast/agent-tools/plugins/kast"));
    const references = join(plugin, "references");
    if (!lstatSync(references).isDirectory()) return { type: "rejected", reason: "RESOURCE_REJECTED" };
    for (const name of RESOURCE_NAMES) {
      const resource = lstatSync(join(references, name));
      if (!resource.isFile() || resource.size > MAX_RESOURCE_BYTES) {
        return { type: "rejected", reason: "RESOURCE_REJECTED" };
      }
    }
    const file = openSync(join(references, "onboarding.md"), constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    let bytes: Uint8Array;
    try {
      const resource = fstatSync(file);
      if (!resource.isFile()) return { type: "rejected", reason: "RESOURCE_REJECTED" };
      if (resource.size > MAX_CONTEXT_BYTES) return { type: "rejected", reason: "CONTEXT_TOO_LARGE" };
      const buffer = Buffer.alloc(MAX_CONTEXT_BYTES + 1);
      const length = readSync(file, buffer, 0, buffer.length, 0);
      if (length > MAX_CONTEXT_BYTES) return { type: "rejected", reason: "CONTEXT_TOO_LARGE" };
      if (length !== resource.size) return { type: "rejected", reason: "RESOURCE_UNAVAILABLE" };
      bytes = buffer.subarray(0, length);
    } finally {
      closeSync(file);
    }
    let brief: string;
    try {
      brief = new TextDecoder("utf-8", { fatal: true }).decode(bytes).trimEnd();
    } catch (error) {
      if (!(error instanceof TypeError)) throw error;
      return { type: "rejected", reason: "RESOURCE_REJECTED" };
    }
    if (!brief.trim() || brief.includes("{{")) return { type: "rejected", reason: "RESOURCE_REJECTED" };
    const text = brief + "\nWorkspace: " + JSON.stringify(workspace) +
      "\nQuery schema: " + JSON.stringify(join(references, "query-schema.json")) +
      "\nQuery examples: " + JSON.stringify(join(references, "query-examples.json"));
    if (Buffer.byteLength(text + "\n") > MAX_CONTEXT_BYTES) return { type: "rejected", reason: "CONTEXT_TOO_LARGE" };
    return { type: "ready", text };
  } catch (error) {
    if (!(error instanceof Error) || !("code" in error)) throw error;
    return { type: "rejected", reason: "RESOURCE_UNAVAILABLE" };
  }
}

// Pi owns MCP discovery, permissions, connection lifetime, and tool execution.
export default function (pi: ExtensionAPI) {
  const installation = join(homedir(), ".local/share/kast/installation");
  pi.registerMcpServer("kast", {
    command: join(installation, "bin/kast-mcp-complete"),
    timeout: 180,
  });
  let context: ContextResult | { type: "not_started" } = { type: "not_started" };
  pi.on("session_start", (_event, ctx) => {
    context = { type: "not_started" };
    context = readContext(installation, ctx.cwd);
    if (context.type === "rejected") {
      ctx.ui.notify("Kast session context is unavailable (" + context.reason + "). Native MCP remains registered.", "warning");
    }
  });
  pi.on("before_agent_start", event => {
    switch (context.type) {
      case "ready": return { systemPrompt: event.systemPrompt + "\n\n" + context.text };
      case "not_started":
      case "rejected": return;
    }
  });
}
