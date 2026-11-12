import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
  let activeSession = {};
  let preparation = "Kast defers workspace preparation to the first semantic query. Session hooks do not grant trust or start a Host.";
  pi.on("session_start", (_event, ctx) => {
    const session = {};
    activeSession = session;
    preparation = "Kast defers workspace preparation to the first semantic query. Session hooks do not grant trust or start a Host.";
    void pi.exec("kast", ["--internal-entrypoint=query", "hook", "session-start", "--workspace", ctx.cwd], { timeout: 90_000 }).then(
      result => {
        if (activeSession !== session) return;
        preparation = result.stdout.trim() ? result.stdout.slice(0, 8192) : "Kast session context returned no observable state. Inspect kast status for passive evidence.";
        if (result.code !== 0) ctx.ui.notify("Kast session context was rejected. " + preparation, "warning");
      },
      () => {
        if (activeSession !== session) return;
        preparation = "Kast session context could not be observed. Inspect kast status for passive evidence.";
        ctx.ui.notify(preparation, "warning");
      },
    );
  });
  pi.on("before_agent_start", event => ({ systemPrompt: event.systemPrompt + "\n\n" + preparation }));
}
