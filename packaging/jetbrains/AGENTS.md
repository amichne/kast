# JetBrains Packaging Guide

This directory owns Kast's off-Marketplace JetBrains feed template and typed
runtime compatibility source.

`updatePlugins.xml` is the hand-authored custom-repository template. Its plugin
ID and IDEA build floor must match `backend-idea`; its URL must name the exact
GitHub Release tag and unsigned ZIP. The release workflow replaces only
`#tag#` and `#version#` and uploads the rendered file beside the ZIP. There is
no Pages repository, signer source, certificate rotation, or generated
repository manifest.

`runtime-compatibility.json` is the only checked-in owner for the IDEA build
range and explicit runtime compatibility pairs. A pair names exact plugin and
CLI releases or an explicitly adjacent pair, positive protocol and workspace
metadata revisions, runtime identity, and a complete disjoint classification
of required and optional capabilities. Do not infer a range, wildcard releases,
or compatibility fallback from the repository feed source.

`runtime-compatibility.json` is compiled into runtime admission and remains
covered by its focused contract. It is not a JetBrains feed input and is not
published as a release asset.

After changing this boundary, run:

```console
.github/scripts/test-runtime-compatibility-contract.sh
.github/scripts/test-release-workflow-contract.sh
```
