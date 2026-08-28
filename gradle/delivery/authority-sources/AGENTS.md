# Delivery authority source guide

This directory owns the immutable byte authorities consumed by KVP-001. The typed Kotlin delivery
program declares every candidate path and expected SHA-256 digest; filenames and ordering never
establish source identity.

- `persisted-goal.txt` is the exact user objective recovered from persisted Kast conversation state.
  Its terminal newline is significant.
- The clean-slate graph and plan are superseded design inputs retained for contradiction analysis.
- The IntelliJ substrate program is retained as design evidence, not as an executable authority.

Do not normalize, format, regenerate, or hand-edit these files. Any byte change must update the
typed source digest, requirement authority when applicable, contradiction record, deterministic
projections, and KVP-001 proof in one reviewed change. The path-specific `.gitattributes` exception
preserves significant trailing spaces in the superseded plan without weakening checks elsewhere.
