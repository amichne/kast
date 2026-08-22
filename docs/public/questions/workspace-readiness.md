# What is Kast ready to inspect?

A running process is not yet proof that the repository model and compiler
evidence are ready. Ask for workspace evidence before you interpret a later
symbol, relation, diagnostic, or change result.

```console
cd /path/to/kotlin-repository
kast workspace inspect
```

## The answer belongs to one exact root

Kast canonicalizes the working directory and associates one isolated runtime
with that exact root. Two checkouts of the same Git repository are two runtime
identities. The foreground state of IntelliJ IDEA or Android Studio does not
select the workspace.

This prevents an answer from silently borrowing indexes, Gradle ownership, or
source state from another checkout.

## Read readiness as evidence

The result identifies `workspace.inspect` and the semantic generation that
produced its payload.

<div class="kast-grid kast-outcome-grid" markdown>

<div class="kast-card kast-tone-evidence" markdown>

**Complete**

The exact root has a published workspace model and satisfies the operation's
full readiness contract.

</div>

<div class="kast-card kast-tone-effect" markdown>

**Qualified**

The returned workspace facts remain usable, and the response names the
readiness limitation that prevents a stronger claim.

</div>

<div class="kast-card kast-tone-muted" markdown>

**Rejected**

Kast could not establish the workspace evidence required by the operation. No
workspace payload is presented as success.

</div>

</div>

`kast status` answers whether the root's runtime is running, stopped, or stale.
`kast workspace inspect` answers whether semantic workspace evidence is
available. Keep those two questions separate.

## Carry the generation forward

Candidates, exact selectors, relations, diagnostics, and change identities are
issued against a semantic generation. If the workspace moves, Kast rejects
stale proof instead of treating an old identity as current.

Once readiness is sufficient, [establish the exact declaration](declaration-identity.md)
needed by the next question.
