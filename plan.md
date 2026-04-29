Repository: amichne/kast

## Overview
Restructure the published documentation rooted at `docs/index.md` to optimize for quick onboarding, seamless usage, and clear "user-not-contributor" separation. The changes span the nav structure in `zensical.toml`, content edits to existing pages, and two new pages.

## 1. Update nav structure in `zensical.toml`

Change the `nav` array (lines 16–48) to this order:
```toml
nav = [
  { "Overview" = "index.md" },
  { "Getting started" = [
    { "Install" = "getting-started/install.md" },
    { "Quickstart" = "getting-started/quickstart.md" },
  ]},
  { "For agents" = [
    { "Overview" = "for-agents/index.md" },
    { "Talk to your agent" = "for-agents/talk-to-your-agent.md" },
    { "Install the skill" = "for-agents/install-the-skill.md" },
    { "Direct CLI usage" = "for-agents/direct-cli.md" },
  ]},
  { "Recipes" = "recipes.md" },
  { "What can Kast do?" = [
    { "Understand symbols" = "what-can-kast-do/understand-symbols.md" },
    { "Trace usage" = "what-can-kast-do/trace-usage.md" },
    { "Refactor safely" = "what-can-kast-do/refactor-safely.md" },
    { "Validate code" = "what-can-kast-do/validate-code.md" },
    { "Manage workspaces" = "what-can-kast-do/manage-workspaces.md" },
  ]},
  { "CLI cheat sheet" = "cli-cheat-sheet.md" },
  { "Backends" = "getting-started/backends.md" },
  { "Kast vs LSP" = "architecture/kast-vs-lsp.md" },
  { "Reference" = [
    { "API reference" = "reference/api-reference.md" },
    { "API specification" = "reference/api-specification.md" },
    { "Error codes" = "reference/error-codes.md" },
  ]},
  { "Troubleshooting" = "troubleshooting.md" },
  { "Architecture" = [
    { "How Kast works" = "architecture/how-it-works.md" },
    { "Behavioral model" = "architecture/behavioral-model.md" },
  ]},
  { "Changelog" = "changelog.md" },
]
```

Key changes: "For agents" promoted up, "Backends" moved to top-level, "Kast vs LSP" promoted to top-level, "Architecture" demoted to bottom, two new pages added (recipes.md, cli-cheat-sheet.md).

## 2. Edit `docs/index.md`

### 2a. Fix the 60-second snippet (lines 78–93)
Replace `kast daemon start --workspace-root=/path/to/your/project` with `kast workspace ensure --backend-name=standalone --workspace-root=$(pwd)`. Replace `kast resolve --workspace-root=/path/to/your/project ...` with `kast resolve --workspace-root=$(pwd) ...`. Add a prerequisite line above the code block: "Run from the root of any Kotlin project. Requires Java 21+."

### 2b. Restructure "Next steps" grid (lines 99–134)
Remove the "Dive into the architecture" card. Replace it with a "Common recipes" card linking to `recipes.md`. The four cards should be: Get started, See what kast can do, Use kast from an agent, Common recipes.

### 2c. Shorten "Two independent runtime modes" section (lines 38–49)
Replace the table with two sentences and a link: "kast runs as a standalone daemon or inside IntelliJ. Both expose the same JSON-RPC contract. Compare backends →"

## 3. Edit `docs/getting-started/install.md`

### 3a. Reorder: move "Choose your setup" table (lines 15–20) below the one-line install section
The first thing after the intro should be the install command, not a decision table.

### 3b. Wrap the wizard walkthrough (lines 52–69) in a collapsible admonition
Use `??? info "What the wizard does"` so it doesn't dominate the page.

### 3c. Remove "Starting the standalone backend" section (lines 138–155)
This duplicates quickstart.md. The install page should end with "Verify the install" and link to quickstart.

### 3d. Wrap the config file section (lines 114–136) in a collapsible
Use `??? info "Where kast stores configuration"`.

## 4. Edit `docs/getting-started/quickstart.md`

### 4a. Replace placeholder paths with $(pwd)
Change `--workspace-root=/absolute/path/to/workspace` to `--workspace-root=$(pwd)` in all commands. Add a callout: "Run these commands from the root of your Kotlin project."

### 4b. Add an offset hint after the resolve command (around line 89)
Add a tip: "Open any .kt file, find a function name, and count the byte offset from the start of the file. Or use `grep -bo 'functionName' file.kt` to find it."

## 5. Edit `docs/for-agents/index.md`

### 5a. Add a quick-start block after the intro paragraph (after line 14)
Add a 3-command block: `kast install skill`, `kast workspace ensure --workspace-root=$(pwd)`, and a comment "Agent can now use kast".

## 6. Edit `docs/for-agents/install-the-skill.md`

### 6a. Wrap "What the skill contains" (lines 53–82) in a collapsible
Use `??? info "What the skill directory contains"`.

### 6b. Wrap "How agents locate the Kast binary" (lines 84–105) in a collapsible
Use `??? info "How agents locate the kast binary"`.

## 7. Create `docs/recipes.md` (NEW)

Create a new page with task-oriented, copy-paste recipes. Each recipe should be a collapsible section (`??? example`) with:
- A task-oriented title
- 2–4 copy-pasteable commands using $(pwd)
- A one-sentence explanation of the output
- A link to the relevant deep-dive page

Include these recipes:
1. Find all usages of a function (resolve → references)
2. See who calls a function (resolve → call-hierarchy --direction=INCOMING)
3. Rename a symbol safely (rename → review → apply-edits)
4. Check if code compiles (diagnostics)
5. Find all implementations of an interface (implementations)
6. Explore a file's structure (outline)
7. Find a class by name (workspace-symbol)
8. Clean up imports (optimize-imports → apply-edits)

Use frontmatter: title "Recipes", description "Task-oriented copy-paste examples for common kast workflows", icon "lucide/book-open".

## 8. Create `docs/cli-cheat-sheet.md` (NEW)

Create a new page with scannable tables of every CLI command grouped by category. No JSON-RPC, no example responses — just command syntax and one-line descriptions.

Categories:
- Workspace lifecycle (ensure, status, stop, refresh)
- Read operations (resolve, references, call-hierarchy, type-hierarchy, outline, workspace-symbol, workspace-files, implementations, diagnostics, code-actions, completions, capabilities, health, insertion-point)
- Mutations (rename, apply-edits, optimize-imports)
- Setup (install skill)

Include the Tier 1 / Tier 2 distinction from architecture/how-it-works.md lines 201–228.

Use frontmatter: title "CLI cheat sheet", description "Every kast command at a glance", icon "lucide/list".

## 9. Edit `docs/getting-started/backends.md`

### 9a. Fix line 128-130
Replace "The CLI never starts a backend for you. You must run `kast daemon start` yourself..." with "The CLI does not implicitly start a backend when you run an analysis command like `resolve` or `references`. Use `workspace ensure` to explicitly start a backend before running queries."

### 9b. Update "Next steps" (lines 148–151)
Replace the link to how-it-works.md with links to quickstart and manage-workspaces.

## 10. Edit `docs/what-can-kast-do/refactor-safely.md`

### 10a. Add a "Verify the result" section after "Apply edits" (after line 204)
Show `kast resolve` or `kast diagnostics` as a verification step after applying edits.

### 10b. Replace the "Next steps" link to behavioral-model.md (line 249)
Replace with a link to troubleshooting for the conflict error case.

## 11. Edit `docs/what-can-kast-do/validate-code.md`

### 11a. Add a CI gate callout after the diagnostics section
Show a minimal 3-command CI script (ensure → diagnostics → stop).

### 11b. Promote the "Refresh before diagnosing" tip (line 69) to a warning admonition
Change from `!!! tip` to `!!! warning`.

## 12. Edit `docs/what-can-kast-do/manage-workspaces.md`

### 12a. Update "Next steps" (lines 169–172)
Replace the link to how-it-works.md with a link to troubleshooting.

## 13. Edit `docs/troubleshooting.md`

### 13a. Remove the "Development and CI" section (lines 143–163)
This is contributor-only content about drift tests and model regeneration.

### 13b. Add a "Diagnostics return stale results" entry under "Analysis results"
Symptom: diagnostics don't reflect recent file changes. Fix: run `kast workspace refresh`.

## 14. Edit `docs/reference/api-specification.md`

### 14a. Wrap "Regenerating the spec" (lines 64–72) in a collapsible
Use `??? info "For contributors: regenerating the spec"`.

## 15. Edit `docs/architecture/how-it-works.md`

### 15a. Add a banner at the top (after frontmatter)
Add: "This page explains how Kast works internally. You don't need to read it to use Kast. For usage documentation, start with the [Quickstart](../getting-started/quickstart.md)."

### 15b. Wrap the module ownership table (lines 68–83) in a collapsible
Use `??? info "Module ownership (for contributors)"`.

## 16. Edit `docs/architecture/behavioral-model.md`

### 16a. Add a banner at the top
Add: "This page explains the rules behind Kast results. Read it when you need to understand why a result looks the way it does."
