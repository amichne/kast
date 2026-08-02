# JetBrains Packaging Guide

This directory owns the typed compatibility source for an installed IntelliJ
runtime. Kast does not publish or install a foreground JetBrains plugin.

`runtime-compatibility.json` is the only checked-in owner for the supported
IntelliJ build range and explicit runtime compatibility pairs. The installed
application is a versioned input to Kast's isolated headless runtime. It is not
a foreground lifecycle, readiness, routing, or semantic authority. Do not infer
a range, wildcard releases, or compatibility fallback.

`runtime-compatibility.json` is compiled into runtime admission and remains
covered by its focused contract. It is not a JetBrains feed and is not
published as a release asset.

After changing this boundary, run:

```console
.github/scripts/runtime/test-runtime-compatibility-contract.sh
.github/scripts/release/test-release-workflow-contract.sh
```
