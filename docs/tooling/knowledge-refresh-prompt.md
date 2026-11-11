# Background knowledge refresh

Work only in this isolated checkout. Do not commit, push, open a pull request,
change Git configuration, fetch, install schedulers, or publish anything.
Do not print credentials, source payloads, or unbounded diagnostics.

Use the native OpenWiki begin, plan, next, submit, and finish lifecycle.
Follow the tool's returned obligations and stop if an obligation cannot be met.
Examine stale Claims against their cited current sources; do not update dates blindly.
For each association older than five days relative to HEAD commit time, genuinely
review the associated sources and claim before updating reviewedSourceRevision.
Preserve unsupported or uncertain claims as unresolved; never manufacture proof.
Update only docs/public/, docs/okf-public.json, openwiki/, or
docs/tooling/knowledge-refresh/ status artifacts. Do not change other paths.
Run focused checks relevant to your edits. Report failures and unreviewed work.
The caller retains the worktree for human review and integration.

For scheduling, configure a host scheduler separately with an explicit invocation
of this tool using --run and an external state directory. No scheduler is installed
by this procedure. In an ephemeral cloud environment, invoke it on environment
startup; persistence requires a separately configured host scheduler.
