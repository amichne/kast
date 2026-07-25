# ADR agent guide

This directory contains only current, durable decisions that are not obvious
from code, tests, or published documentation. It is not a history archive and
is not part of the Zensical site.

## Rules

- Keep a record only while its decision affects current or future work.
- Ground every retained record in current source and executable proof.
- Update a record when its invariant remains but its source ownership changes.
- Delete superseded records, completed migrations, timelines, issue narratives,
  and decisions fully expressed elsewhere. Git preserves them.
- State why a record still needs to exist.
- Do not add Zensical front matter or navigation entries.

## Verify

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
git diff --check
```
