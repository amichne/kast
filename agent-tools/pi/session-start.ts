import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

// The same passive command that the other agents run as a session hook. Pi has no hook configuration.
const COMMAND =
  'PATH="${HOME}/.local/bin:${PATH}" exec kast --internal-entrypoint=query hook session-start --workspace "$1" --host plain';
const TIMEOUT_MS = 90_000;

export default function (pi: ExtensionAPI) {
  let pending: string | undefined;

  // A resumed session already holds its context.
  pi.on("session_start", async (event, ctx) => {
    const begins = event.reason === "startup" || event.reason === "new";
    pending = begins ? await context(pi, ctx.cwd) : undefined;
  });

  pi.on("before_agent_start", async () => {
    const content = pending;
    pending = undefined;
    return content === undefined
      ? undefined
      : { message: { customType: "kast-session-start", content, display: false } };
  });
}

/** A missing command, a failure, or an unknown reply adds nothing to the session. */
async function context(pi: ExtensionAPI, directory: string): Promise<string | undefined> {
  try {
    const result = await pi.exec("/bin/bash", ["-c", COMMAND, "kast", directory], { timeout: TIMEOUT_MS });
    if (result.code !== 0) return undefined;
    const value: unknown = JSON.parse(result.stdout)?.additionalContext;
    return typeof value === "string" && value !== "" ? value : undefined;
  } catch {
    return undefined;
  }
}
