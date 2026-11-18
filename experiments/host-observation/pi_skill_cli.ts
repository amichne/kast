// Isolate the public CLI and the linked skill page from the grader and repository.
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "kast",
    label: "Kast CLI",
    description: 'Run the installed Kast CLI with argv after kast. ["guide"] reads public-cli.md.',
    parameters: Type.Object({ argv: Type.Array(Type.String()) }),
    async execute(_id, { argv }) {
      if (argv.length === 1 && argv[0] === "guide") {
        return {
          content: [{ type: "text", text: readFileSync(process.env.PI_TRIAL_GUIDE!, "utf8") }],
          details: {},
        };
      }
      const discovery = argv[0] === "describe" || argv.join(" ") === "--help" ||
        argv.join(" ") === "query --help";
      const live = process.env.PI_TRIAL_LIVE === "1" && argv[0] === "query";
      if ((!discovery && !live) || argv.some(arg => /^--(workspace|request|internal-entrypoint)(=|$)/.test(arg))) {
        throw new Error("Command outside this trial");
      }
      const result = spawnSync(process.env.PI_TRIAL_KAST!, argv, {
        cwd: process.env.PI_TRIAL_WORKSPACE,
        encoding: "utf8",
        timeout: 60_000,
        maxBuffer: 4 * 1024 * 1024,
      });
      if (result.error) throw result.error;
      return {
        content: [{ type: "text", text: result.stdout + result.stderr }],
        details: { argv, status: result.status, stdout: result.stdout },
        isError: result.status !== 0,
      };
    },
  });
}
