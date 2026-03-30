# Docs agent guide

The `docs` unit records architecture decisions, operator workflows, and active
implementation notes for Kast.

## Ownership

Keep these docs tightly coupled to the real implementation and decision record.

- Keep docs aligned with the code that exists today. Mark planned or missing
  behavior explicitly instead of implying it already works.
- Use ADRs for durable decisions. Add a new ADR or append follow-up context
  when the architecture changes materially rather than silently rewriting
  history.
- Keep the README, operator guide, and remaining-work notes consistent with the
  current capability surface of the IntelliJ and standalone hosts.
- Prefer precise statements over broad claims. If evidence is partial, narrow
  the wording and make the uncertainty explicit.
- When behavior changes, update the docs in the same change set if the user-
  facing contract or operator workflow moved.

## Verification

Review documentation changes against the code and neighboring docs before
finishing.

- Re-read modified docs against the implementation before finishing.
- Check nearby docs for stale references whenever you change module behavior,
  routes, or capabilities.
