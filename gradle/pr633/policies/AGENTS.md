# PR 633 path policies

The cleanup policy admits only its predecessor surface. The PR 633 policy is a deny-list; the
program's KTP633-010 through KTP633-070 task scopes are the sole allow-list authority.

The program admits an `AGENTS.md` only when its ancestor-guide marker is present and the guide is
an ancestor of another admitted changed non-guide path. A guide alone never expands product scope.
