---
type: How-to Guide
title: Troubleshoot Kast Setup
description: Recover a Kast installation by rerunning its atomic setup transaction.
tags: [troubleshooting, setup]
code_sources:
  - path: install.sh
  - path: cli-rs/src/install/bundle_install.rs
---

# Troubleshoot Kast Setup

Rerun the same setup operation for every installation or readiness failure:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

For a local or pinned bundle:

```console
./install.sh --source /path/to/kast-platform-vX.Y.Z.tar.gz
```

Setup reports the failed phase, release and manifest digests, verified artifact
paths, backup location, and exact rerun command. Do not edit `current`, receipts,
or individual artifacts by hand.

If setup reports a checksum or shape failure, download or rebuild the bundle
again. If it reports active-root verification failure, the prior release has
already been restored. If another setup is running, let the lock holder finish
and rerun the same command. Stale staging directories are discarded
automatically on the next invocation.
