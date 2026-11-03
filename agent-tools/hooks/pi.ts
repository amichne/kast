import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
  pi.on("session_start", async (_event, ctx) => {
    const result = await pi.exec("kast", ["hook", "session-start", "--workspace", ctx.cwd], { timeout: 3_780_000 });
    if (result.code !== 0) ctx.ui.notify("Kast workspace preparation failed. " + result.stdout.slice(0, 2048), "warning");
  });
}
