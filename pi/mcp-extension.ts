import { homedir } from "node:os";
import { join } from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

// Pi owns MCP discovery, permissions, connection lifetime, and tool execution.
export default function (pi: ExtensionAPI) {
  const installation = join(homedir(), ".local/share/kast/installation");
  const script = join(installation, "share/kast/agent-tools/plugins/kast/hooks/session_start.py");
  pi.registerMcpServer("kast", {
    command: join(installation, "bin/kast-mcp-complete"),
    timeout: 180,
  });
  let context: string | undefined;
  pi.on("session_start", async (_event, ctx) => {
    context = undefined;
    const result = await pi.exec(join(homedir(), ".local/bin/uv"),
      ["run", "--offline", "--locked", "--script", script, "pi", ctx.cwd], { timeout: 10_000 });
    if (result.code !== 0 || result.killed || !result.stdout.trim() || Buffer.byteLength(result.stdout) > 8193) {
      throw new Error("Kast session context is unavailable; examine the plugin resources and uv installation");
    }
    context = result.stdout.trimEnd();
  });
  pi.on("before_agent_start", event => {
    if (context === undefined) throw new Error("Kast session context is unavailable");
    return { systemPrompt: event.systemPrompt + "\n\n" + context };
  });
}
