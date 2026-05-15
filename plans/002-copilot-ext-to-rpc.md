## Objective

Replace `callKastSkill(command, args)` in `extension.mjs` with a unified `callKast(method, params)` function that builds a JSON-RPC envelope and calls `kast rpc`. All 12 tool handlers switch from per-wrapper-command dispatch to the single `rpc` entry point.

## Repository: michne/kast

## Files to modify

### 1. `.github/extensions/kast/extension.mjs`

**Replace `callKastSkill` (lines 162-196) with `callKast`:**
```js
async function callKast(method, params) {
  const bin = await resolveKastBinary();
  if (!bin) {
    return JSON.stringify({
      ok: false,
      stage: "extension.resolve",
      message: `kast binary not resolved: ${resolveError ?? "unknown"}`,
    });
  }
  const request = JSON.stringify({ jsonrpc: "2.0", method, params: params ?? {}, id: 1 });
  const cmd = `${JSON.stringify(bin)} rpc ${JSON.stringify(request)}`;
  const { ok, stdout, stderr, code } = await execBash(cmd);
  const out = stdout.trim();
  if (!out) {
    return JSON.stringify({
      ok: false,
      stage: "extension.exec",
      message: `kast rpc ${method} produced no output (exit ${code})`,
      errorText: stderr.trim() || null,
    });
  }
  try {
    JSON.parse(out);
    return out;
  } catch {
    return JSON.stringify({
      ok: false,
      stage: "extension.parse",
      message: `kast rpc ${method} returned non-JSON (exit ${code})`,
      raw: out,
      errorText: stderr.trim() || null,
    });
  }
}
```

**IMPORTANT:** The `callKast` function now sends requests through the JSON-RPC protocol of the analysis server (`AnalysisDispatcher`), NOT through the skill wrapper layer. The tool handlers need to map to the correct JSON-RPC method names and use the `AnalysisDispatcher` param schemas (the `api.contract.query.*` types), NOT the wrapper request types from `api.wrapper.*`.

However, this is a problem: the current extension tool schemas use wrapper-style params (e.g., `symbol` as a string name for resolve, rather than `filePath`+`offset`). The `kast rpc` passthrough sends directly to the daemon's JSON-RPC dispatch, which expects `AnalysisDispatcher` method schemas (position-based queries like `{"filePath":"/path","offset":42}`).

**Resolution:** The skill wrapper commands (`resolve`, `references`, `callers`, `scaffold`, `write-and-validate`, `rename`) perform name-to-position resolution and multi-step orchestration that the raw JSON-RPC methods don't do. For Phase 2, keep `callKastSkill` as-is but rename it to `callKastLegacy`, and have the tool handlers that need wrapper semantics (resolve, references, callers, scaffold, rename, write-and-validate) continue calling through the legacy path. Tools that map 1:1 to JSON-RPC methods (workspace-files, workspace-search, workspace-symbol, file-outline, diagnostics, metrics) switch to `callKast`.

Specifically:
- `kast_workspace_files` → `callKast("workspace/files", args)` 
- `kast_workspace_symbol` → `callKast("workspace-symbol", args)`
- `kast_workspace_search` → `callKast("workspace/search", args)`
- `kast_file_outline` → `callKast("file-outline", args)`
- `kast_diagnostics` → `callKast("diagnostics", args)`

For the remaining tools that need the wrapper's name-resolution and orchestration logic (resolve, references, callers, scaffold, rename, write-and-validate, metrics), keep using the skill wrapper path for now via `callKastSkill`. These will be addressed in Phase 4 when we decide whether to fold NamedSymbolResolver logic into the daemon-side dispatch or keep a thin CLI-side orchestration layer.

Actually, re-evaluating: the cleanest approach for Phase 2 is to keep ALL handlers calling through the skill wrapper path (via `kast <wrapper> <json>`) since we haven't deleted those yet. The migration to `kast rpc` for the extension happens in Phase 4 when we decide on the orchestration story. 

**Revised approach for Phase 2:** Instead of migrating the extension in this phase, skip to Phase 3 (doc migration) and fold the extension migration into Phase 4 together with the deletion. This avoids a half-migrated state.

**Actually, the simplest correct approach:** Keep Phase 2 as written but acknowledge that the wrapper-style commands (resolve by name, scaffold, write-and-validate, rename, callers) provide orchestration logic on top of raw JSON-RPC. The `kast rpc` passthrough is for direct JSON-RPC method access. The extension tools that need orchestration should continue to call through the skill wrapper CLI surface until Phase 4 introduces a `kast rpc` method that provides equivalent orchestration (e.g., `symbol/resolve-by-name` as a new JSON-RPC method in AnalysisDispatcher), OR we move the orchestration into the extension itself.

**Final decision for Phase 2:** Do NOT migrate the extension yet. Mark this phase as "update supportsWrapperCommands to also accept `kast rpc`" and add a new helper function `callKast` alongside `callKastSkill` (don't remove callKastSkill yet). Switch ONLY the tools that map 1:1 to daemon JSON-RPC methods. Leave the rest for Phase 4.

### Update `supportsWrapperCommands` (line 158-160)
Change the check to verify the binary supports `kast rpc` (e.g., `kast rpc '{"jsonrpc":"2.0","method":"health","id":1}'` returns valid JSON).

### Update `onSessionStart` hook
Change the background `workspace ensure` call to use `kast up` instead:
```js
execBash(`${JSON.stringify(bin)} up --workspace-root=${JSON.stringify(REPO_ROOT)} --accept-indexing=true`)
```

### Update tool handler for 1:1 mappable tools
Switch these tool handlers from `callKastSkill` to `callKast`:
- `kast_workspace_files`: `callKast("workspace/files", args)`
- `kast_workspace_symbol`: `callKast("workspace-symbol", args)`  
- `kast_workspace_search`: `callKast("workspace/search", args)`
- `kast_file_outline`: `callKast("file-outline", args)`
- `kast_diagnostics`: `callKast("diagnostics", args)`

Leave these on `callKastSkill` (they need wrapper orchestration):
- `kast_resolve`, `kast_references`, `kast_callers`, `kast_scaffold`, `kast_rename`, `kast_write_and_validate`, `kast_metrics`

### 2. `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/EmbeddedCopilotExtensionResources.kt`
No changes needed — the manifest stays the same, only `extension.mjs` content changes.

## Verification gate
- All 12 `kast_*` tools still work in a Copilot session
- `kast up` is used for session start instead of `workspace ensure`
- The 5 switched tools return raw JSON-RPC responses (slightly different shape than wrapper responses — verify the agent can still parse them)
- If the raw JSON-RPC response shape is incompatible with agent expectations, revert those 5 tools to `callKastSkill` and note that ALL tools need to stay on the wrapper path until Phase 4
