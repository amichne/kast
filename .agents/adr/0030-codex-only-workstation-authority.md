# ADR 0030: Codex-only workstation authority

Status: Superseded by ADR 0031

Date: 2026-07-20

## Context

ADR 0029 installed both a provider-neutral global Kast skill and the native
`kast@kast` Codex plugin on macOS. Codex is now the sole workstation agent
interface, so the global projection duplicates routing authority and can
shadow the selected plugin generation.

## Decision

The processless macOS machine bundle contains the CLI, matched IDEA plugin, and
Codex marketplace only. Reconciliation selects `kast@kast` and never creates
`~/.agents/skills/kast`.

On upgrade, reconciliation removes that path only when it is a symlink whose
target is the former machine-owned `resources/kast-skill` directory. Files,
directories, and unrelated symlinks remain untouched.

IDEA workspace metadata is sufficient for macOS agent readiness. Repository-
local and non-workstation provider-neutral skills remain outside this machine
authority.

The Codex manifest retains repository, homepage, author, license, and package
metadata without dedicated policy-page URLs. The public site contains no
separate policy pages.

This record supersedes ADR 0029's global-skill bundle, reconciliation, and
readiness decisions and ADR 0026's policy-page URL decision.

## Validation

Machine-authority tests prove the reduced bundle, safe legacy cleanup, IDEA
installation, and Codex selection. Codex generation and documentation
contracts prove the removed manifest fields and pages stay absent.
