---
title: Walk symbols and structure in the terminal
description: Use `kast demo` to search indexed symbols, walk references,
  inspect source previews, and render a spatial source-index tree.
icon: lucide/git-branch
---

# Walk symbols and structure in the terminal

`kast demo` is an interactive view over the source index. It answers a
practical navigation question: from this symbol, who depends on it, what
does it depend on, and what source line should I inspect next?

By default, `kast demo` opens the existing symbol-walk view. Pass
`--view spatial` to render the same source-index data as a structural
tree over workspace, module/source-set, file, and declaration nodes.

The command reads `source-index.db` directly. It does not write source
files, apply edits, or mutate the workspace. The only source reads are
for previewing the selected declaration or reference location.

## Start from a symbol or a search

Use `--symbol` when you already have a fully-qualified name. Use
`--query` when you want to search first. If neither is provided, the
demo opens on the most referenced indexed symbols.

=== "Start from a symbol"

    ```console title="Open a known symbol"
    kast demo \
      --workspace-root="/absolute/path/to/workspace" \
      --symbol "com.example.OrderService.processOrder"
    ```

=== "Start from search"

    ```console title="Open with a query"
    kast demo \
      --workspace-root="/absolute/path/to/workspace" \
      --query "OrderService"
    ```

=== "JSON snapshot"

    ```console title="Non-interactive snapshot"
    kast demo \
      --workspace-root="/absolute/path/to/workspace" \
      --symbol "com.example.OrderService" \
      --json
    ```

=== "Spatial view"

    ```console title="Open the spatial tree"
    kast demo \
      --workspace-root="/absolute/path/to/workspace" \
      --view spatial \
      --symbol "com.example.OrderService"
    ```

=== "Spatial JSON"

    ```console title="Non-interactive spatial snapshot"
    kast demo \
      --workspace-root="/absolute/path/to/workspace" \
      --view spatial \
      --symbol "com.example.OrderService" \
      --json
    ```

## Symbol View

The default symbol view has three working areas. On wide terminals,
they appear as side-by-side panes. On narrow terminals, the same
information stacks vertically.

| Pane | Purpose |
|------|---------|
| Symbols | Search results with kind, incoming count, outgoing count, and module. |
| Current symbol | The selected symbol identity, reference counts, module, visibility, and edge mix. |
| Incoming | Indexed symbols or files that reference the current symbol. |
| Outgoing | Indexed symbols referenced by the current symbol. |
| Source preview | Source lines around the selected declaration or reference offset. |
| Walk stack | Recently visited symbols so `b` can step back through the walk. |

Rows marked with `>` are walkable because the index recorded a source
symbol identity. Rows marked with `-` are file-level references: the
source file and offset are known, but no source symbol was recorded for
that row.

## Controls

The controls are intentionally small enough to demo without explaining a
separate command language.

| Key | Action |
|-----|--------|
| `/` | Enter search mode and focus the symbol list. |
| `Enter` | Open the selected search result or walk into the selected relation. |
| `Tab`, `Shift+Tab` | Move focus between symbol, incoming, and outgoing panes. |
| `h`, `l`, arrow left, arrow right | Move focus between panes. |
| `j`, `k`, arrow down, arrow up | Move within the focused pane. |
| `b`, `Backspace` | Return to the previous symbol in the walk stack. |
| `r` | Reload the current symbol from `source-index.db`. |
| `q`, `Esc`, `Ctrl+C` | Quit. |

Search mode accepts normal text input. `Enter` opens the selected
search result. `Esc` leaves search mode without opening a result.

## Spatial View

The spatial view uses the same source-index database, but projects
structural containment into a terminal-native tree. The v1 structure is
workspace, module/source-set, file, and declaration. Literal AST nodes
are represented in the JSON contract for later support, but are not
required for this first renderer.

The spatial snapshot keeps structure and semantics separate. Tree edges
are containment edges. Reference and call-flow overlays are optional
rendered edges and do not change node placement.

| Node identity | Meaning |
|---------------|---------|
| `compilerSymbol` | The currently anchored symbol resolved through the indexed symbol model. |
| `sourceIndexDeclaration` | A declaration row from `source-index.db`. |
| `fileOutlineNode` | A file node from the source-index file manifest. |
| `syntheticAggregate` | Workspace or module/source-set grouping added by the demo. |
| `literalAstNode` | Reserved for a future literal AST provider. |
| `structuralOnly` | Reserved for structural nodes with no compiler-backed identity. |

The spatial TUI supports structural movement and the core symbolic
workflow:

| Key | Action |
|-----|--------|
| `/` | Enter symbol search. |
| `Enter` | Anchor the spatial tree on the selected search result. |
| `Tab`, `Shift+Tab` | Move focus between search, spatial canvas, and details. |
| Arrow up/down, `j`, `k` | Move through visible spatial nodes. |
| Arrow left/right, `h`, `l` | Move to parent or first child. |
| `Space` | Collapse or expand the selected subtree. |
| `O` | Cycle structure-only, references, and call-flow overlays. |
| `P` | Switch top-down and oblique projection. |
| `F`, `C` | Recenter the selected node. |
| `W`, `A`, `S`, `D`, `E`, `Q` | Move or zoom the camera. |
| `b`, `Backspace` | Return to the previous anchored symbol. |
| `r` | Rebuild the snapshot from `source-index.db`. |
| `q`, `Esc`, `Ctrl+C` | Quit. |

The repository includes an asciinema recording of this path at
[docs/assets/spatial-demo.cast](../assets/spatial-demo.cast). It uses a
seeded local source index and exercises overlay switching, projection
switching, spatial movement, symbol search, and call-flow overlay
rendering.

## JSON mode

`--json` returns the same information the TUI uses, which makes each
view testable without terminal automation. The smoke test in this repo
seeds a small source index, calls `kast demo --json --symbol app.A`,
and also calls `kast demo --view spatial --json --symbol app.A`.

```json title="Shape of a demo snapshot" hl_lines="5 8 12 16"
{
  "ok": true,
  "snapshot": {
    "mode": "symbolWalk",
    "current": {
      "fqName": "com.example.OrderService"
    },
    "incoming": [
      { "fqName": "com.example.CartController", "walkable": true }
    ],
    "outgoing": [
      { "fqName": "com.example.OrderRepository", "walkable": true }
    ],
    "preview": {
      "title": "Declaration: OrderService",
      "focusedLine": 12
    }
  },
  "schemaVersion": 3
}
```

```json title="Shape of a spatial snapshot" hl_lines="5 9 15 18"
{
  "ok": true,
  "snapshot": {
    "mode": "spatialAst",
    "selection": {
      "nodeId": "symbol:com.example.OrderService"
    },
    "tree": {
      "rootId": "workspace",
      "nodes": [
        { "id": "workspace", "identity": "syntheticAggregate" }
      ]
    },
    "visibleNodes": [
      { "nodeId": "symbol:com.example.OrderService", "selected": true }
    ],
    "overlays": [
      { "mode": "structureOnly", "enabled": true }
    ]
  },
  "schemaVersion": 3
}
```

## Source-index requirements

The demo expects the current source-index schema used by `cli-rs`.
It verifies that the database exists, has the published source-index
schema version, and contains the tables needed for symbol names,
persistent symbol FTS,
declarations, file metadata, file manifests, and symbol references.

If no database exists for the workspace, run:

```console title="Create or warm the index"
kast up --workspace-root="/absolute/path/to/workspace"
```

If you already have a database, pass it directly:

```console title="Use an explicit SQLite file"
kast demo \
  --workspace-root="/absolute/path/to/workspace" \
  --database="/absolute/path/to/source-index.db"
```

The source preview is best-effort. If a file has moved or is not
readable, the demo still shows the indexed symbol graph and reports the
read failure in the preview pane.
