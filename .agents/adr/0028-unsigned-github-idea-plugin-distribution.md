# ADR 0028: Unsigned GitHub IDEA plugin distribution

Status: Accepted

Date: 2026-07-17

## Context

ADR 0023 selected signed, immutable IDEA plugin artifacts, a generated
GitHub Pages repository, signer enrollment, and publication-time verification.
Kast will not publish the IDEA plugin to JetBrains Marketplace and does not
need that cryptographic publication system. Its unconfigured signer currently
prevents otherwise valid GitHub releases.

Kast still needs a GitHub-hosted plugin ZIP, a native IDE update path, and a
way to keep the Homebrew CLI and IDEA plugin on a tested pair. JetBrains IDEs
already understand custom repository feeds and expose a command-line
`installPlugins` operation. Reimplementing either mechanism in Kast adds no
product value.

This record supersedes ADR 0023 only for plugin signing, immutable plugin
publication, custom-repository hosting, installer ownership, and restart-free
release gates. ADR 0023's typed runtime compatibility, backend-private index,
exact-root admission, lifecycle teardown, and semantic cockpit decisions remain
in force. It also supersedes older records only where they forbid `install.sh`
from asking an IDE to install the plugin through the IDE's own command-line
surface; direct profile writes and directory links remain retired.

## Decision

Each GitHub release publishes:

- one unsigned `kast-idea-<tag>.zip` produced by `:backend-idea:buildPlugin`;
- one `updatePlugins.xml` whose plugin entry names that exact release ZIP; and
- the existing non-IDEA release products.

Kast does not publish this plugin to JetBrains Marketplace or GitHub Pages. The
IDEA release job does not sign the ZIP, verify a signature, enroll a
certificate, run Marketplace publication verification, assemble IDEA artifact
provenance, or require immutable-repository settings. The static
`packaging/jetbrains/updatePlugins.xml` template is rendered with the release
tag and version and uploaded beside the ZIP.
While the release is still a draft, reruns may replace only these two assets
with `--clobber`; IDEA byte-identity replay is not a retained property.

Users enroll this stable custom-repository URL once for IDE-owned update
discovery:

```text
https://github.com/amichne/kast/releases/latest/download/updatePlugins.xml
```

For initial installation, the root macOS installer may invoke a closed IDE's
supported `installPlugins` command with the version-specific feed for the
installed Homebrew CLI release. That delegates all plugin-directory mutation
to the IDE and prevents `latest` from silently selecting a different version.
JetBrains' headless command does not replace an already-installed plugin, so
Kast does not present it as an updater. Existing installations update through
the enrolled custom repository; installation from the exact release ZIP is the
documented fallback. Kast does not recreate profile links or write IDE plugin
directories directly.

CLI and plugin updates are not an atomic transaction. The checked-in runtime
compatibility source remains the authority for whether
a CLI, plugin, protocol, metadata revision, runtime identity, and capability
set may operate together. The plugin reads the exact Homebrew receipt-owned CLI
path at project open; `kast agent verify --workspace-root <root>` is the
on-demand admission check. Neither `PATH` nor the update feed is compatibility
evidence.

JetBrains dynamic plugin unload is a best-effort lifecycle property, not a
release guarantee. Kast keeps services disposable and removes known invalid
extension registrations. An update may apply without restart when the running
IDE accepts unload; otherwise the supported behavior is the IDE's restart
fallback. Kast does not implement classloader tricks, manual JAR replacement,
or a second hot-reload mechanism.

## Source ownership

| Contract | Owner | Validation |
| --- | --- | --- |
| Plugin ID and unload-safe extension registration | `backend-idea/src/main/resources/META-INF/plugin.xml` | IDEA plugin tests and a real IDE lifecycle probe when claiming dynamic eligibility |
| Unsigned ZIP and release feed upload | `.github/workflows/release.yml` | `.github/scripts/test-release-workflow-contract.sh` |
| Feed entry template | `packaging/jetbrains/updatePlugins.xml` | release workflow contract |
| Initial CLI-to-IDE install delegation and update guidance | `install.sh` | `.github/scripts/test-macos-installer-contract.sh` |
| Supported runtime pairs | `packaging/jetbrains/runtime-compatibility.json` | `.github/scripts/test-runtime-compatibility-contract.sh` and `kast agent verify` |

## Consequences

The release no longer fails because IDEA signing secrets, a signer fingerprint,
GitHub Release immutability, or Pages deployment is absent. GitHub becomes the
only plugin artifact host, while the IDE remains the only owner of installed
plugin files and update application.

The unsigned ZIP has no Kast-enforced signer identity or byte-identity replay
guarantee. That is an explicit product tradeoff, not a missing release gate.
Users who require those properties must not treat this distribution channel as
providing them.

Restart-free updates cannot be promised uniformly. Removing a known unload
blocker makes dynamic application possible to retest, but a rejected unload
still requires restart.
