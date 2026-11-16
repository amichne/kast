---
type: Module Guide
title: "Documentation implementation and repository navigation"
description: "Routes documentation tooling, policy, and read-only repository discovery."
resource: "repo://docs/internal"
tags: [kast, navigation]
timestamp: "2026-11-16T00:00:00Z"
code_sources:
  - path: docs/internal/tooling/knowledge_check.py
  - path: docs/internal/tooling/knowledge_refresh.py
  - path: docs/internal/tooling/publication.py
  - path: docs/internal/navigation.py
  - path: docs/internal/tests/test_navigation.py
  - path: .agents/repository-surfaces.json
  - path: bin/kast-repo
---

# Documentation implementation

- [The documentation policy](documentation-structure.md) defines source owners and qualification.
- [The tooling guide](tooling/README.md) routes parsers, assessment, projections, and browser evidence.
- Use `bin/kast-knowledge-refresh` for native maintenance. Keep root `openwiki/` and its producer-owned sidecars.
- [Native inventory evidence](reviews/native-library-classification.md) records the pinned Linux payload and its limits.

## Repository navigation

- Run `mise exec -- bin/kast-repo list` for declared public paths. `discover` is an alias for `list`.
- Run `mise exec -- bin/kast-repo read README.md` for a UTF-8 file. Paths are canonical and repository-relative, even from another working directory.
- Add `--internal` to include declared private surfaces. Narrow with `--scope packaging`, `--scope documentation`, or `--scope knowledge`. These scopes require `--internal`; `--scope public` does not.
- `.agents/repository-surfaces.json` owns surface selection. It is not a claim or distribution inventory. Keep entries disjoint and preserve each declared file or tree.
- Root `openwiki/` remains the native managed knowledge bundle. `--internal --scope knowledge` permits read-only access to visible, non-ignored bundle files. Hidden native state, including checkpoints, is excluded. This command neither changes native state nor establishes evidence freshness, completion, or publication qualification.
- The default selects public entries before filesystem traversal. Every scope excludes hidden paths, Git-ignored input, cache directories, and credential-shaped names. Symlinks reject rather than expand access. This policy governs this command, not other filesystem tools; it does not detect secrets embedded in otherwise eligible source text.
- The command requires Python 3.12 or 3.13 and Git. Ignore-check failure rejects access. It uses no network, package installation, maintenance, or repository writes.
- Each operation emits one JSON object: `listed`, `read`, or `rejected`. Success exits zero; rejection exits one with a finite `reason`. `--help` emits ordinary help text.
- Lists default to 100 files; `--limit` accepts 1–1000. `truncated: true` means more eligible files exist. Narrow the path prefix or scope; a bounded list does not establish complete discovery.
- Reads default to 65536 bytes; `--max-bytes` accepts 1–1048576. Overflow rejects without returning partial content. Binary and invalid UTF-8 input reject.
- Run focused tests with `PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=docs/internal mise exec -- uv run --locked --project packaging pytest docs/internal/tests/test_navigation.py -q` for isolated discovery cases.
