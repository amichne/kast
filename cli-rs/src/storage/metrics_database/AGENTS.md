# Metrics database instructions

This directory owns the current read-only SQLite implementation behind
`kast developer inspect metrics` and agent impact fallback.

- Do not add a second database-path authority; use
  `config::workspace_database_path`.
- Keep result and error models separate from SQL execution.
- Keep presentation in `output` or the calling command.
- Impact identity fails closed on FQ name, canonical path, declaration offset,
  and kind. Functions and properties degrade when the index cannot prove
  overload isolation.
- Add no cancellation, deadline, or progress-control branch unless a real
  production request can set and exercise it.
