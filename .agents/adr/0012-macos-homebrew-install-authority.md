# ADR 0012: macOS Homebrew install authority

Status: Accepted

Date: 2026-07-09

This ADR supersedes the install-identity portions of ADR 0007: macOS plugin
setup authority and ADR 0010: brew-style macOS onboarding installer. Their
public installer shape and plugin-owned workspace setup rules remain in force.

## Decision

Homebrew is the sole machine-install authority on macOS. A successful,
version-coupled CLI and JetBrains plugin convergence writes one trusted receipt
at:

```text
~/Library/Application Support/Kast/homebrew-install.json
```

The receipt has schema version 1 and authority `macos-homebrew`. It records the
exact executable under the installed formula prefix, the CLI version, the
plugin cask token, and the plugin version. The CLI and plugin versions must
equal the running Kast version. Both the Rust CLI and IntelliJ plugin parse and
validate the receipt before using it; they do not infer authority from `PATH`,
a global config file, or a legacy local install manifest.

The IntelliJ plugin invokes the exact receipt binary. It fails before workspace
preparation when the receipt is missing, malformed, version-skewed, outside the
formula prefix, or points to a missing or non-executable file.

The installer and `kast developer machine plugin` converge the plugin instead
of force-reinstalling it. They skip the cask mutation when the installed cask
already matches the CLI version, while still repairing profile links, defaults,
and the receipt. A cask metadata version that differs from the CLI is a blocking
error.

IntelliJ IDEA and Android Studio must be closed before an install or update
mutates the formula, cask, or plugin links. Detection is fail-closed with a
stable diagnostic. The public flow never escalates with `sudo`.

## Legacy local installs

`~/.local/share/kast/install.json` and its managed shim are inactive on macOS
once a valid Homebrew receipt exists. Readiness reports `macos-homebrew` as the
authority and identifies an earlier managed shim only when its exact contents
and target match Kast's generated legacy shim.

`kast repair --for machine --apply`, invoked through the authoritative receipt
binary, may back up and remove that confirmed legacy shim and manifest when
their parent directories are writable. It does not delete unknown files, use
`sudo`, or fail the Homebrew install when an inactive legacy path is owned by
an administrator. A copy-paste cleanup command is shown only when the shadow is
confirmed managed, writable, and the Homebrew executable is the next `kast` on
`PATH`.

## Source of truth

| Contract | Source |
| --- | --- |
| Receipt schema, write, and Rust validation | `cli-rs/src/install/macos_homebrew_receipt.rs` |
| Plugin convergence and IDE-closed preflight | `cli-rs/src/install/homebrew_idea_plugin.rs`, `cli-rs/src/install/jetbrains_profiles.rs`, `cli-rs/src/install/idea_plugin_entrypoint.rs` |
| Install authority, PATH shadow diagnosis, and repair | `cli-rs/src/self_mgmt.rs`, `cli-rs/src/install/repair.rs` |
| IntelliJ receipt validation and exact binary use | `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/MacosHomebrewInstallReceipt.kt`, `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastProjectOpenProfileAutoInit.kt` |
| Remote installer orchestration | `install.sh` |
| Homebrew formula delegation | `packaging/homebrew/Formula/kast.rb` |

## Validation gates

At minimum, changes to this authority boundary run:

```console
.github/scripts/test-macos-installer-contract.sh
python3 packaging/homebrew/scripts/test-formulas.py
cargo test --manifest-path cli-rs/Cargo.toml --locked --test machine_plugin_smoke --test machine_plugin_repair_smoke --test ready_repair_smoke
./gradlew :backend-idea:test
.github/scripts/test-docs-content-contract.sh
```

Shared Rust install changes also run full Rust tests, formatting, and clippy.
Plugin activation changes also run `./gradlew buildIdeaPlugin`. Any future
macOS authority change must supersede this ADR and migrate both receipt readers
in the same change.
