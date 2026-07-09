---
title: macOS Developer Machine
description: Install Kast on a macOS developer machine with the root installer.
icon: lucide/apple
---

# macOS Developer Machine

Use this path when you work on a local macOS project with IntelliJ IDEA or
Android Studio. The normal install is intentionally short: run the installer,
restart the IDE if prompted, and open the project.

## Install The Machine Distribution

Run the root installer once for the machine.

```console title="Install Kast on macOS"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer is macOS-only. It uses Homebrew to install the global `kast`
binary and installs or refreshes the matching IDEA or Android Studio plugin.
It explains planned machine changes before mutating anything.

## Open Your Project

Restart IntelliJ IDEA or Android Studio after the installer updates the plugin,
then open the project. The plugin prepares the project so agents can use Kast
without a separate directory-specific install step.

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

Continue with [how Kast thinks about evidence](../learn/evidence-model.md) to
understand what agents do with the installed semantic backend.
