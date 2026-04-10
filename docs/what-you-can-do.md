---
title: What you can do
description: See the questions Kast answers once a workspace is loaded.
icon: lucide/help-circle
---

Kast is easiest to understand as a set of questions about a Kotlin workspace.
This page maps those questions to the semantic answers Kast returns, without
dropping you into command syntax or request payload details.

## What is the symbol at this location?

Use this when you know the file and position and need the exact declaration
identity behind the code under your cursor or offset.

- **Capability:** Symbol resolution
- **Returns:** The symbol's fully qualified name, kind, declaration location,
  visibility modifier, and related metadata such as supertypes when available.
- **Important details:** Resolution is position-based, not name-based. The
  file must be part of the current workspace session.

## Where is this symbol used?

Use this when you want real usages of one resolved symbol across the
workspace, not every place the same text happens to appear.

- **Capability:** Find references
- **Returns:** A list of usage locations across the workspace, a `searchScope`
  object that describes the visibility-scoped search that ran, and optionally
  the declaration alongside the reference list.
- **Important details:** Results are workspace-scoped. Kast follows semantic
  resolution, so it separates one symbol from other declarations with the same
  name. Read `searchScope.exhaustive` before treating the result as complete —
  private and internal symbols are searched within narrower scopes than public
  ones.

## What calls this function?

Use this when you want callers or callees rooted at a specific declaration and
care more about structure than about raw search hits.

- **Capability:** Call hierarchy
- **Returns:** A tree of incoming callers or outgoing callees, plus traversal
  stats and node-level truncation metadata.
- **Important details:** Traversal is intentionally bounded by depth, total
  edges, per-node child count, and timeout. Read `stats` and any
  `truncation` fields before you treat the tree as complete. This capability
  is available in the standalone backend only. The IntelliJ plugin backend
  does not yet support it.

## What breaks if I rename this?

Use this when you want to see refactoring impact before anything writes to
disk.

- **Capability:** Rename planning
- **Returns:** A proposed edit set, the affected files, the file hashes that
  lock the plan to the current workspace state, and a `searchScope` object
  that describes the visibility-scoped search that ran.
- **Important details:** The plan starts from a resolved symbol. The returned
  hashes are part of conflict detection when you later apply the edits. Read
  `searchScope.exhaustive` to confirm the rename plan covers all usages within
  the expected scope.

## Does this file have errors?

Use this when you want the current analysis or compiler diagnostics for one or
more files in the workspace.

- **Capability:** Diagnostics
- **Returns:** Diagnostics with locations and code metadata when the runtime
  can provide them.
- **Important details:** Diagnostics only cover files in the current workspace
  session and reflect the state the daemon currently sees.

## What declarations does this file contain?

Use this when you want a structured overview of the declarations in one file
without reading every line of source.

- **Capability:** File outline
- **Returns:** A nested tree of OutlineSymbol entries. Each entry includes the
  symbol identity (name, kind, location) and its children. The tree reflects
  the actual nesting of declarations in the file: a top-level class contains
  its member functions and properties as children.
- **Important details:** The outline includes classes, objects, named
  functions, and named properties. It excludes parameters, anonymous elements,
  and local declarations. The file must be part of the current workspace
  session.

## Where is a symbol by name?

Use this when you know what a declaration is called but not where it lives, and
you want to search across the entire workspace.

- **Capability:** Workspace symbol search
- **Returns:** A list of matching Symbol entries from across the workspace,
  with optional pagination metadata when the result set exceeds the requested
  limit.
- **Important details:** Search is case-insensitive substring by default. Pass
  regex mode for pattern-based matching. You can filter by symbol kind (class,
  function, property). Results are capped by a configurable limit (default
  100). Read `page.truncated` before treating the list as complete. This is a
  name-based search across all workspace files, not a position-based
  resolution.

## Apply these edits safely

Use this when you already have a prepared edit plan and want Kast to write it
to disk with conflict checks in place.

- **Capability:** Edit application
- **Returns:** The applied edits or file operations and the affected, created,
  and deleted files.
- **Important details:** Kast checks the expected file hashes first, writes
  synchronously, and refreshes the daemon before it returns.

## Next steps

Move to the behavioral model when you want to know how to interpret the
output, or install Kast when you are ready to run it. To see which
capabilities each backend supports — including the new file outline and
workspace symbol search — check the
[capability comparison table](how-it-works.md#capability-comparison).

- [Things to know](things-to-know.md)
- [Get started](get-started.md)
- [Run analysis commands](run-analysis-commands.md)
