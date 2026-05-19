#!/usr/bin/env node
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { CONFIG_POLICIES, buildClientOptions, buildSessionConfig, isolateMockWorktreeConfig } from "../run-one.mjs";

const permissionLog = [];
const tempRoot = mkdtempSync(resolve(tmpdir(), "kast-run-one-config-"));
const worktreePath = resolve(tempRoot, "run-1", "worktree");
const config = buildSessionConfig({
  configuration: "with_skill",
  model: "gpt-5-mini",
  reasoningEffort: "medium",
  worktreePath,
  policy: CONFIG_POLICIES.with_skill,
  permissionLog,
  callKast: async () => "{}",
  kastBackendMode: "mock",
});

assert.equal(config.hooks, undefined);
assert.equal(config.configDir, resolve(tempRoot, "run-1", "copilot-home"));
assert.deepEqual(JSON.parse(readFileSync(resolve(config.configDir, "settings.json"), "utf8")), {
  disableAllHooks: true,
  extensions: {
    disabledExtensions: ["kast"],
    mode: "disabled",
  },
});

const withoutSkillConfig = buildSessionConfig({
  configuration: "without_skill",
  model: "gpt-5-mini",
  reasoningEffort: "medium",
  worktreePath: resolve(tempRoot, "run-2", "worktree"),
  policy: CONFIG_POLICIES.without_skill,
  permissionLog,
  callKast: async () => "{}",
  kastBackendMode: "mock",
});
assert.deepEqual(
  withoutSkillConfig.onPermissionRequest({ kind: "shell", fullCommandText: "kast rpc '{}'" }, {}),
  { kind: "denied-by-rules" },
);

const toolOnlyConfig = buildSessionConfig({
  configuration: "tool_only",
  model: "gpt-5-mini",
  reasoningEffort: "medium",
  worktreePath: resolve(tempRoot, "run-3", "worktree"),
  policy: CONFIG_POLICIES.tool_only,
  permissionLog,
  callKast: async () => "{}",
  kastBackendMode: "mock",
});
assert.deepEqual(
  toolOnlyConfig.onPermissionRequest(
    {
      kind: "shell",
      fullCommandText:
        'rg -n "^\\s*fun\\s+`|^\\s*fun\\s+[a-zA-Z]" analysis-api/src/test/kotlin/io/github/amichne/kast/api/AnalysisDocsDocumentTest.kt || true',
    },
    {},
  ),
  { kind: "denied-by-rules" },
);
assert.deepEqual(
  toolOnlyConfig.onPermissionRequest(
    {
      kind: "shell",
      fullCommandText: String.raw`perl -0777 -ne 'while(/include\((.*?)\)/sg){$x=$1; while($x=~ /["\']([^"\']+)["\']/g){print "$1\n"}}' settings.gradle.kts`,
    },
    {},
  ),
  { kind: "denied-by-rules" },
);

assert.deepEqual(
  buildClientOptions({
    githubToken: "",
    copilotCliPath: "/tmp/copilot",
    otelPath: "/tmp/otel.jsonl",
    mockCopilotHome: resolve(tempRoot, "run-1", "copilot-home"),
  }),
  {
    useLoggedInUser: true,
    cliPath: "/tmp/copilot",
    copilotHome: resolve(tempRoot, "run-1", "copilot-home"),
    telemetry: {
      exporterType: "file",
      filePath: "/tmp/otel.jsonl",
      captureContent: true,
    },
  },
);

const repoRoot = mkdtempSync(resolve(tmpdir(), "kast-run-one-worktree-"));
execFileSync("git", ["-C", repoRoot, "init"], { stdio: "ignore" });
mkdirSync(resolve(repoRoot, ".github/extensions/kast"), { recursive: true });
mkdirSync(resolve(repoRoot, ".github/hooks"), { recursive: true });
mkdirSync(resolve(repoRoot, ".github/agents"), { recursive: true });
mkdirSync(resolve(repoRoot, ".agents/skills/kast"), { recursive: true });
mkdirSync(resolve(repoRoot, "src"), { recursive: true });
writeFileSync(resolve(repoRoot, ".github/extensions/kast/extension.mjs"), "export default {};\n");
writeFileSync(resolve(repoRoot, ".github/hooks/hooks.json"), "{}\n");
writeFileSync(resolve(repoRoot, ".github/agents/kast.md"), "---\nname: kast\n---\n");
writeFileSync(resolve(repoRoot, ".agents/skills/kast/SKILL.md"), "# kast\n");
writeFileSync(resolve(repoRoot, "src/Main.txt"), "main\n");
execFileSync("git", ["-C", repoRoot, "add", "."], { stdio: "ignore" });
execFileSync(
  "git",
  ["-C", repoRoot, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "init"],
  { stdio: "ignore" },
);

await isolateMockWorktreeConfig(repoRoot);

assert.equal(existsSync(resolve(repoRoot, "src/Main.txt")), true);
assert.equal(existsSync(resolve(repoRoot, ".github/extensions/kast/extension.mjs")), false);
assert.equal(existsSync(resolve(repoRoot, ".github/hooks/hooks.json")), false);
assert.equal(existsSync(resolve(repoRoot, ".github/agents/kast.md")), false);
assert.equal(existsSync(resolve(repoRoot, ".agents/skills/kast/SKILL.md")), false);
assert.equal(String(execFileSync("git", ["-C", repoRoot, "status", "--porcelain"])).trim(), "");

rmSync(tempRoot, { recursive: true, force: true });
rmSync(repoRoot, { recursive: true, force: true });

console.log("All run-one config tests passed.");
