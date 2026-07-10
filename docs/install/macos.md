---
title: macOS Developer Machine
description: Install Kast on a macOS developer machine with the root installer.
icon: lucide/apple
---

# macOS Developer Machine

Use this path when you work on a local macOS project with IntelliJ IDEA or
Android Studio. The normal install is intentionally short: close JetBrains
IDEs, run the installer, and open the project.

## Install The Machine Distribution

Run the root installer once for the machine.

```console title="Install Kast on macOS"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer is macOS-only. It uses Homebrew to install the global `kast`
binary and installs or refreshes the matching IDEA or Android Studio plugin.
It explains planned machine changes before mutating anything. Close IntelliJ
IDEA and Android Studio first; the installer stops before changing Homebrew or
plugin files if either product is running.

Homebrew is the machine-install authority on macOS. After the CLI, matching
plugin, profile links, and defaults converge, Kast records the exact formula
binary in `~/Library/Application Support/Kast/homebrew-install.json`. The
plugin reads that receipt instead of trusting whichever `kast` happens to
appear first on `PATH`.

## Open Your Project

Open IntelliJ IDEA or Android Studio after the installer completes, then open
the project. The plugin prepares the project so agents can use Kast without a
separate directory-specific install step.

Normal developer use does not require running readiness, repair, or setup commands by hand.
Those checks are part of the agent and plugin workflow.

??? question "What the IDE and agents handle"
    On macOS, workspace setup is owned by the IntelliJ plugin. It prepares the
    project guidance and metadata agents need when the project opens.

    The CLI does not install skill-only, runtime-only, Copilot package,
    portable instruction, session hook, generated catalog, workflow helper, or
    resource-only workspace setup on macOS. If prior Kast-managed files are not
    required or recognized by the incoming plugin version, the plugin backs them up and removes them
    from the active setup path.

??? info "Advanced installer controls"
    Most users do not need these commands. They exist for automation, mirrors,
    and support cases.

    ```console title="Refresh or verify the machine install"
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- verify
    ```

    The default Homebrew tap is `amichne/kast`. Pass both `--tap` and
    `--tap-url` when a mirror lives on a custom Git host.

    ```console title="Install from an internal Homebrew tap"
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- install \
      --tap internal/kast \
      --tap-url https://git.example.com/internal/homebrew-kast.git
    ```

    Use `NONINTERACTIVE=1` only when automation has already accepted the
    installer plan.

??? warning "Recover from an older local install"
    An older Kast installation may have left
    `~/.local/share/kast/install.json` and a `~/.local/bin/kast` shim. Do not
    delete them with `sudo`, and do not edit Kast's binary path by hand.

    Close IntelliJ IDEA and Android Studio, then run the update command above.
    Invoke `verify` after opening the project. Kast treats the Homebrew receipt
    as authoritative and reports the old manifest as inactive. If a known,
    writable Kast shim still shadows Homebrew on `PATH`, readiness prints a
    safe cleanup command that invokes the exact Homebrew binary. If the old
    path is administrator-owned or its contents are unknown, Kast leaves it in
    place and reports that no automatic cleanup is safe; ask your machine
    administrator to resolve ownership or PATH policy.

Continue with [how Kast thinks about evidence](../learn/evidence-model.md) to
understand what agents do with the installed semantic backend.
