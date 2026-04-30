
The important design move is that rendering should no longer be a transcript. It should be a **spatial dashboard**:

```text
┌ Kast graph visualizer ───────────────────────────────────────────────┐
│ 12,484 symbols  1,218 files  88,210 refs  depth 4                    │
│ U parent  D/Enter child  ←/→ sibling  A members  Q quit              │
└──────────────────────────────────────────────────────────────────────┘

Path
source file → focal class → selected member/reference

Current
▶ io.github.amichne.kast.cli.MetricsGraphTerminal
  SYMBOL · parent MetricsGraphTerminal.kt · 4 children · 7 attrs

Neighborhood
        parent
          │
  prev ← current → next
          │
      first child

Children
  1. run(...)                         FUNCTION
  2. render(...)                      FUNCTION
  3. sibling(...)                     FUNCTION

Relations
  referenced by  KastCli.writeInteractiveGraph       weight 1
  contains       TerminalRawMode                     weight 1
```

For demo quality, the conversion should emphasize these details:

- Show **names as simple names first**, with FQNs/path detail secondary.
- Show a **breadcrumb/path** every frame so the viewer knows where they are.
- Show **parent/current/children/siblings** in fixed regions, not as a vertical dump.
- Show edge labels as human language: `contains`, `references`, `referenced by`.
- Keep the reducer pure and test it with tracer bullets before polishing the view.
- Keep a non-interactive fallback via `MetricsGraphShell.render(graph)` for redirected stdout/tests.

If you choose Kotter instead, the reducer/view split stays the same, but the loop becomes `session { var cursor by liveVarOf(...); section { render(cursor) }.runUntilKeyPressed(Keys.Q) { onKeyPressed { cursor = reduce(...) } } }`. That is elegant, but I do not think it outweighs Mordant’s existing dependency and lower integration risk here.

One caveat: the file path in the current `/Users/amichne/.codex/worktrees/790c/kast` checkout does not exist; I inspected the existing file at `/Users/amichne/code/kast/kast-cli/src/main/kotlin/io/github/amichne/kast/cli/MetricsGraphTerminal.kt`.
