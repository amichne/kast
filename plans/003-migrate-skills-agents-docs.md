## Objective

Update all agent-facing documentation to reference `kast rpc` as the canonical machine interface and `kast up`/`status`/
`stop` as the human lifecycle commands. Remove references to per-operation CLI commands and wrapper-openapi.yaml from
the doc surface.

## Repository: michne/kast

## Files to modify

### 1. `AGENTS.md`

**Lines 79-94 — Replace the "Mandatory tool routing" table:**

Replace the current 13-row table with:

```markdown
| Operation            | Native tool                        | Bash fallback                                                |
|----------------------|------------------------------------|--------------------------------------------------------------|
| Any analysis/mutation| `kast_<tool>` (native extension)   | `kast rpc '{"method":"<method>","params":{...},"id":1}'`     |
```

Add a note: "The native `kast_*` tools registered by `.github/extensions/kast/extension.mjs` remain the preferred
interface. The `kast rpc` CLI command is the universal fallback — it accepts any JSON-RPC method the daemon supports and
auto-ensures the daemon."

Keep the list of native tool names (`kast_resolve`, etc.) for discoverability, but note they route through `kast rpc`
internally.

**Lines 172-181 — Update "Contract surface inventory":**
Remove `WrapperOpenApiDocument` from the enumeration of consumers. Add `kast rpc` as the machine contract surface.

### 2. `.agents/skills/kast/SKILL.md`

**Lines 40-70 — Update the "Routing" section:**

Keep the routing table (it maps intent to tool name, which is still valid), but update the "Use the native wrappers"
section:

Replace lines 57-70:

```markdown
Use the native tools when the host exposes them (`kast_workspace_files`,
`kast_resolve`, etc.). When native tools are unavailable, use `kast rpc`:

    kast rpc '{"jsonrpc":"2.0","method":"<method>","params":{...},"id":1}'

The `kast rpc` command auto-ensures the daemon — no explicit `workspace ensure` needed.
```

**Line 34 — Remove the note about `references/wrapper-openapi.yaml`:**
Change "Do not load... `references/wrapper-openapi.yaml` during normal navigation" to just "Do not load... during normal
navigation" (remove the wrapper-openapi reference).

### 3. `.agents/skills/kast/references/commands.json`

This file is auto-generated from Kotlin serialization models. It will be regenerated in Phase 4 when the wrapper types
are deleted. For now, add a note in SKILL.md that `commands.json` describes the wrapper command schemas and will be
replaced by the JSON-RPC method catalog.

### 4. `.agents/skills/kast/references/wrapper-openapi.yaml`

Do NOT delete yet (Phase 4 handles deletion). Add a deprecation notice at the top:

```yaml
# DEPRECATED: This file will be removed. Use kast rpc with JSON-RPC methods instead.
```

### 5. `.agents/skills/kast/fixtures/maintenance/references/wrapper-openapi.yaml`

Same deprecation notice.

## Verification gate

- `kast eval skill` still passes (the skill structural checks should still work with the updated SKILL.md)
- Agent sessions can still discover and use the kast tools via the updated docs
- No references to defunct per-operation CLI commands (like `kast resolve`, `kast references`) as primary recommended
  paths — they should only appear as legacy/deprecated if at all
