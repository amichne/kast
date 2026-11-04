import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
  let preparation = "Kast workspace preparation is starting in the background. Use kast workspace wait --wait TIMEOUT_MS or --wait indefinite when readiness is needed.";
  pi.on("session_start", (_event, ctx) => {
    preparation = "Kast workspace preparation is starting in the background. Use kast workspace wait --wait TIMEOUT_MS or --wait indefinite when readiness is needed.";
    void pi.exec("kast", ["hook", "session-start", "--workspace", ctx.cwd, "--wait", "none"], { timeout: 90_000 }).then(
      result => {
        preparation = result.stdout.trim() ? result.stdout.slice(0, 8192) : "Kast workspace preparation returned no observable state. Inspect kast status before relying on readiness.";
        if (result.code !== 0) ctx.ui.notify("Kast workspace preparation was rejected. " + preparation, "warning");
      },
      () => {
        preparation = "Kast workspace preparation could not be observed. Inspect kast status and retry explicit workspace preparation when appropriate.";
        ctx.ui.notify(preparation, "warning");
      },
    );
  });
  pi.on("before_agent_start", event => ({ systemPrompt: event.systemPrompt + "\n\n" + preparation }));
}
