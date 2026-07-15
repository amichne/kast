# JetBrains Repository Packaging Guide

This directory owns the authored configuration for Kast's off-Marketplace
JetBrains plugin repository.

`plugin-repository.json` is the checked-in owner for the stable feed URL,
plugin identity, immutable GitHub Release asset URL template, active signing
certificate fingerprint, and explicit certificate-rotation state. Keep the
production signer unconfigured until its public certificate fingerprint is
known; never substitute a fixture, secret, or workflow variable.

`runtime-compatibility.json` is the only checked-in owner for the IDEA build
range and explicit runtime compatibility pairs. A pair names exact plugin and
CLI releases or an explicitly adjacent pair, positive protocol and workspace
metadata revisions, runtime identity, and a complete disjoint classification
of required and optional capabilities. Do not infer a range, wildcard releases,
or compatibility fallback from the repository feed source.

`updatePlugins.xml` and `plugin-repository-manifest.json` are generated Pages
outputs. `kast-runtime-compatibility.json` is a generated immutable release
asset with its own artifact ledger and release provenance. Do not edit any of
these outputs by hand. The renderers must validate
the finalized signed ZIP cryptographically against its public certificate and
signer-bound release provenance before either file is emitted. Feed advancement
also requires GitHub's immutable-release attestation and an exact tag-to-commit
match. A rotation overlap must name two distinct enrolled signers; unknown or
incomplete rotation state fails closed.

After changing this boundary, run:

```console
.github/scripts/test-jetbrains-plugin-repository-contract.sh
.github/scripts/test-runtime-compatibility-contract.sh
.github/scripts/test-idea-plugin-signing-contract.sh
.github/scripts/test-release-workflow-contract.sh
```
