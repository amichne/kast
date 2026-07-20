---
type: Playbook
title: Troubleshoot Kast
description: Recover the macOS IDEA and Codex workstation flow from visible symptoms.
tags: [troubleshooting, macos, codex]
code_sources:
  - path: install.sh
  - path: cli-rs/src/machine.rs
  - path: cli-rs/src/codex/hook.rs
---

# Troubleshoot Kast

Start with the visible symptom. Recovery returns to the same supported path:
close the IDE, reconcile through the installer, open the exact root, and start
a new Codex task.

## Diagnostic matrix

| Symptom | Cause | Recovery |
| --- | --- | --- |
| The installer says the IDE is open | A loaded plugin cannot be replaced safely. | Quit every IDEA and Android Studio process, then rerun the installer. |
| The installer cannot find an IDE profile | IDEA or Android Studio has not created its user profile. | Start the IDE once, quit it, then rerun the installer. |
| Codex does not load Kast | The task started before `kast@kast` was selected. | Finish installation and start a new Codex task. |
| Codex reports no prepared workspace | The exact task root has not been opened by the IDEA plugin. | Open that project or worktree in the IDE, wait for loading, then start a new task. |
| Codex reports an incompatible release pair | IDEA updated the plugin independently or installation was interrupted. | Close the IDE and run the installer in update mode. |
| A startup or diagnostics hook reports a failure | Advisory launch or diagnostics context was unavailable. | Confirm the exact project is open; continue if the task does not need semantic evidence, otherwise reinstall and start a new task. |

## Restore the matched bundle

Quit the IDE and run:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update
```

Open the exact root again and start a new Codex task. The installer does not
replace user-owned files at the former global-skill location; it removes only
the obsolete Kast-owned symlink from earlier workstation bundles.
