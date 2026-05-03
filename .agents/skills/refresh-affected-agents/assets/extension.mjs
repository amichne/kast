/**
 * refresh-affected-agents extension
 *
 * Keep AGENTS.md maintenance hook-backed without moving workflow logic into
 * `.github/hooks/hooks.json`.
 *
 *  1. `refresh_affected_agents` wraps the skill's diff helper and returns the
 *     exact AGENTS.md files in scope plus the drafting contract path.
 *  2. `onPostToolUse` re-runs the helper after successful file mutations and
 *     injects a one-time reminder when the current git diff puts AGENTS files
 *     in scope.
 *
 * The extension reuses the skill's script and contract directly so the diff
 * logic and drafting rules stay in one place.
 */

import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { joinSession } from "@github/copilot-sdk/extension";

const HERE = dirname(fileURLToPath(import.meta.url));
const SKILL_ROOT_RELATIVE = join(".agents", "skills", "refresh-affected-agents");
const FIND_SCRIPT_RELATIVE = join(SKILL_ROOT_RELATIVE, "scripts", "find_affected_agents.py");
const CONTRACT_RELATIVE = join(
    SKILL_ROOT_RELATIVE,
    "references",
    "agents-update-contract.md",
);
const DEFAULT_HEAD = "HEAD";
const MAX_BUFFER = 16 * 1024 * 1024;
const MAX_SUMMARY_ITEMS = 4;
const MUTATING_TOOLS = new Set([
    "apply_patch",
    "create",
    "edit",
    "idea-create_new_file",
    "idea-reformat_file",
    "idea-rename_refactoring",
    "idea-replace_text_in_file",
    "kast_rename",
    "kast_write_and_validate",
]);

let cachedWorkspaceRoot = null;
let resolveError = null;
let warnedMissingSupport = false;
let lastScopeFingerprint = null;

function findWorkspaceRoot(startPath = HERE) {
    let current = resolve(startPath);
    while (true) {
        const scriptPath = join(current, FIND_SCRIPT_RELATIVE);
        const contractPath = join(current, CONTRACT_RELATIVE);
        if (existsSync(scriptPath) && existsSync(contractPath)) {
            return current;
        }
        const parent = dirname(current);
        if (parent === current) return null;
        current = parent;
    }
}

function supportPaths() {
    if (cachedWorkspaceRoot) {
        return {
            workspaceRoot: cachedWorkspaceRoot,
            helperScript: join(cachedWorkspaceRoot, FIND_SCRIPT_RELATIVE),
            contractPath: join(cachedWorkspaceRoot, CONTRACT_RELATIVE),
        };
    }
    const workspaceRoot = findWorkspaceRoot();
    if (!workspaceRoot) {
        resolveError =
            "could not locate .agents/skills/refresh-affected-agents support files from extension path";
        return null;
    }
    cachedWorkspaceRoot = workspaceRoot;
    return {
        workspaceRoot,
        helperScript: join(workspaceRoot, FIND_SCRIPT_RELATIVE),
        contractPath: join(workspaceRoot, CONTRACT_RELATIVE),
    };
}

function normalizeRepoPath(repo, cwd) {
    const base = cwd || process.cwd();
    if (!repo) return base;
    return isAbsolute(repo) ? repo : resolve(base, repo);
}

function execFileAsync(file, args, options = {}) {
    return new Promise((resolvePromise) => {
        execFile(
            file,
            args,
            {
                ...options,
                maxBuffer: MAX_BUFFER,
            },
            (error, stdout, stderr) => {
                resolvePromise({
                    ok: !error,
                    code: error?.code ?? 0,
                    error,
                    stdout: String(stdout ?? ""),
                    stderr: String(stderr ?? ""),
                });
            },
        );
    });
}

async function runPythonScript(scriptPath, args, options = {}) {
    const candidates =
        process.platform === "win32"
            ? ["py", "python", "python3"]
            : ["python3", "python"];
    let missingInterpreterResult = null;

    for (const candidate of candidates) {
        const result = await execFileAsync(candidate, [scriptPath, ...args], options);
        if (result.ok || result.error?.code !== "ENOENT") {
            return result;
        }
        missingInterpreterResult = result;
    }

    return (
        missingInterpreterResult ?? {
            ok: false,
            code: "ENOENT",
            stdout: "",
            stderr: "python interpreter not found",
        }
    );
}

async function loadAffectedAgents(args = {}, cwd = process.cwd()) {
    const paths = supportPaths();
    if (!paths) {
        return {
            ok: false,
            stage: "extension.resolve",
            message: resolveError ?? "refresh-affected-agents support files are unavailable",
        };
    }

    const repoPath = normalizeRepoPath(args.repo, cwd);
    const scriptArgs = ["--repo", repoPath, "--format", "json"];
    if (args.base) {
        scriptArgs.push("--base", args.base, "--head", args.head || DEFAULT_HEAD);
    }

    const result = await runPythonScript(paths.helperScript, scriptArgs, { cwd: repoPath });
    if (!result.ok) {
        return {
            ok: false,
            stage: "extension.exec",
            message: "find_affected_agents.py failed",
            exitCode: result.code,
            errorText: result.stderr.trim() || null,
        };
    }

    const output = result.stdout.trim();
    if (!output) {
        return {
            ok: false,
            stage: "extension.exec",
            message: "find_affected_agents.py produced no output",
            exitCode: result.code,
            errorText: result.stderr.trim() || null,
        };
    }

    try {
        const payload = JSON.parse(output);
        return {
            ok: true,
            payload: {
                ...payload,
                helper_script: paths.helperScript,
                contract_path: paths.contractPath,
            },
        };
    } catch {
        return {
            ok: false,
            stage: "extension.parse",
            message: "find_affected_agents.py returned non-JSON output",
            raw: output,
            errorText: result.stderr.trim() || null,
        };
    }
}

function scopeFingerprint(payload) {
    return JSON.stringify({
        mode: payload.mode ?? null,
        base: payload.base ?? null,
        head: payload.head ?? null,
        agent_files: payload.agent_files ?? [],
    });
}

function summarizeCoveredPaths(paths = []) {
    const shown = paths.slice(0, MAX_SUMMARY_ITEMS);
    const remaining = paths.length - shown.length;
    const preview = shown.length ? shown.join(", ") : "no covered paths reported";
    return remaining > 0 ? `${preview}, +${remaining} more` : preview;
}

function summarizeAgent(agent) {
    return `- ${agent.path} (scope ${agent.scope}; covers ${summarizeCoveredPaths(agent.covered_paths)})`;
}

function buildReminderContext(payload) {
    const diffSurface =
        payload.mode === "range"
            ? `range ${payload.base ?? "?"}...${payload.head ?? DEFAULT_HEAD}`
            : "current worktree";
    const agentFiles = Array.isArray(payload.agent_files) ? payload.agent_files : [];
    const shownAgents = agentFiles.slice(0, MAX_SUMMARY_ITEMS).map(summarizeAgent);
    const remainingAgents = agentFiles.length - shownAgents.length;
    if (remainingAgents > 0) {
        shownAgents.push(`- +${remainingAgents} more AGENTS.md files in scope`);
    }

    return [
        `AGENTS refresh candidate detected from the ${diffSurface}.`,
        "Use `refresh_affected_agents` before touching any AGENTS.md file so the diff-derived scope stays explicit.",
        `Read \`${CONTRACT_RELATIVE}\` before editing a target.`,
        "In-scope AGENTS.md files:",
        ...shownAgents,
        "Refresh only the local instructions proven stale by the covered paths. If the diff does not change the instructions, leave the file unchanged.",
    ].join("\n");
}

async function logMissingSupportWarning() {
    if (warnedMissingSupport) return;
    warnedMissingSupport = true;
    await session.log(
        `refresh-affected-agents extension unavailable: ${resolveError ?? "support files missing"}`,
        { level: "warning" },
    );
}

const session = await joinSession({
    tools: [
        {
            name: "refresh_affected_agents",
            description:
                "Update only the `AGENTS.md` files that sit on modified git paths by deriving scope from `git diff`. Returns the exact in-scope files from the skill helper plus the drafting contract path.",
            parameters: {
                type: "object",
                properties: {
                    repo: {
                        type: "string",
                        description:
                            "Optional path inside the target git repository. Defaults to the active working directory.",
                    },
                    base: {
                        type: "string",
                        description:
                            "Base ref for range mode. When set, the helper runs `git diff <base>...<head>`.",
                    },
                    head: {
                        type: "string",
                        description: "Head ref for range mode. Defaults to HEAD when `base` is set.",
                    },
                },
            },
            skipPermission: true,
            handler: async (args, invocation) => {
                const result = await loadAffectedAgents(args, invocation?.cwd || process.cwd());
                return {
                    textResultForLlm: JSON.stringify(result.ok ? result.payload : result, null, 2),
                    resultType: result.ok ? "success" : "failure",
                };
            },
        },
    ],
    hooks: {
        onSessionStart: async () => {
            warnedMissingSupport = false;
            lastScopeFingerprint = null;
            const paths = supportPaths();
            if (!paths) {
                await logMissingSupportWarning();
                return {};
            }
            await session.log("refresh-affected-agents extension ready", { ephemeral: true });
            return {
                additionalContext:
                    "Native AGENTS maintenance tool available: `refresh_affected_agents`. " +
                    `It wraps \`${FIND_SCRIPT_RELATIVE}\` and points at \`${CONTRACT_RELATIVE}\`. ` +
                    "After successful file mutations, the extension may inject a reminder when the current git diff puts local AGENTS.md files in scope. " +
                    "Only refresh the AGENTS.md files named by the helper, and only when the diff changes their local instructions.",
            };
        },
        onPostToolUse: async (input) => {
            if (!MUTATING_TOOLS.has(input.toolName)) return;
            if (input.toolName === "refresh_affected_agents") return;
            const resultType = input.toolResult?.resultType;
            if (resultType && resultType !== "success") return;

            const scope = await loadAffectedAgents({}, input.cwd || process.cwd());
            if (!scope.ok) {
                await logMissingSupportWarning();
                return;
            }

            const agentFiles = Array.isArray(scope.payload.agent_files)
                ? scope.payload.agent_files
                : [];
            if (agentFiles.length === 0) {
                lastScopeFingerprint = null;
                return;
            }

            const fingerprint = scopeFingerprint(scope.payload);
            if (fingerprint === lastScopeFingerprint) return;
            lastScopeFingerprint = fingerprint;

            return {
                additionalContext: buildReminderContext(scope.payload),
            };
        },
    },
});
