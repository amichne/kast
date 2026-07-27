# Symbol query instructions

This directory owns current read-only source-index symbol queries.

- Use `config::workspace_database_path`; do not derive another database path.
- Keep request models, ranking/filtering, database reads, and tests separated.
- Preserve semantic, lexical, graph, and structural signals as typed evidence.
- Add no consumer that bypasses the typed backend when compiler evidence is
  required.
