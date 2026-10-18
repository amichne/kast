# Linux evaluation environment

This profile provisions an isolated Linux x86_64 runtime for Kast evaluation.
It does not install personal shell configuration or change credentials.
Runtime directories and evaluation receipts belong outside the repository.

```sh
python3 packaging/cloud/bootstrap.py --root /workspace/pi-eval --with-idea --with-native-fixture --with-native-control
source /workspace/pi-eval/activate.sh
node packaging/cloud/smoke.mjs --refresh-catalog --kast-adapter "$PWD/pi/mcp-extension.ts"
bash packaging/cloud/checks.sh
```

Python 3.12+, curl, archive support, and Git are host prerequisites. The manifest pins
vendor archives and their published SHA-256 digests. The package lock pins Pi and
its transitive packages. Bootstrap runs no package lifecycle scripts and accepts no
IDE license. Repeat the same command to validate the cached archive digests and
reinstall the locked package tree. Use a new runtime directory for a clean setup.
An optional `--download-cache` selects a read-only cache of public vendor archives.
Every archive digest is still checked. Concurrent setup in one runtime is rejected.

Bootstrap also checks the IDE build, bundled Kotlin plugin version, and JBR 25+.
The Host distribution and native test distribution have separate exact pins.
GraalVM supplies Native Image for Control builds; the ordinary JDK supplies Gradle.
The wrapper owns the Gradle distribution checksum. The generated activation file
configures only the owned Gradle directory through the current approved proxy.
Set `PI_EVAL_JAVA_TRUSTSTORE` if the platform provides a different Java certificate store.

The Linux source path admits the standalone vendor layout, bundled JBR and exact
per-user plugin profile. Control archives carry a Linux platform name. Fresh
paired installation requires explicit `--idea-home`, verified local assets, an
absent canonical installation root, and the default XDG data directory. Replacement,
reset, uninstall and the legacy broker service remain unqualified on Linux.
Direct MCP uses its own client process. A virtual display is required for graphical
lifecycle launch; a process start never establishes Host readiness.

The authoritative shell configuration source is `amichne/dotfiles`; the legacy shell-config
repository is not part of this profile. Portable Pi resources must be selected
explicitly into the isolated agent directory. Keep authentication, models,
sessions, extension registrations, and machine settings outside that copy.

```sh
python3 packaging/cloud/portable-profile.py --runtime "$PI_EVAL_ROOT" --dotfiles "$PI_EVAL_ROOT/sources/dotfiles"
```

This opt-in profile copies only `APPEND_SYSTEM.md`, `agents`, and `prompts` from
the pinned public source. It refuses to replace files. It excludes personal shell
startup, language-server paths, credentials, models, and package settings.

## Capability boundaries

| Capability | Provisioning and validation boundary |
| --- | --- |
| Node, Pi, Python, uv | Node and uv archives are pinned; Pi 1.3.0 uses the package lock. Host Python needs 3.12 or 3.13 for the source checks. |
| IntelliJ and Kotlin | Verify exact product, plugin and JBR identities from the pinned archives. A free distribution alone grants no Kast readiness. |
| Gradle and Control | Use the checked wrapper and optional GraalVM. Linux native compilation also needs a C/C++ toolchain and `zlib` headers. |
| Slopsentral | The released adapter and source revision are pinned separately. Source checks and native Pi loader tests prove different boundaries. |
| Portable resources | Only the named public Pi files enter the owned agent directory. The full shell gate has separate OS dependencies. |
| Kast Host/Control installation | Linux supports a fresh local pair at the canonical per-user installation. Actual product installation and imported-model readiness require separate qualification. |
| True headless semantics | Native indexed fixture tests use headless platform APIs. The product Host lifecycle skips headless startup. These are separate execution modes. |
| Virtual display | `Xvfb` supplies an X11 display for a graphical IDE. The Linux launcher preserves the real IDE and waits for the correlated Host handshake. Startup alone grants no semantic authority. |
| Model runs | Need an approved cloud authentication mechanism, working native queries in the exact child environment, and the bounded evaluation plan. |

## Source checks and clean fixtures

Run Slopsentral's actual gates in its pinned source checkout:

```sh
cd "$PI_EVAL_ROOT/sources/slopsentral"
npm ci --ignore-scripts --no-audit --no-fund
node tools/validate-source-graph.mjs
npm run check:pi
TEST_VALE_ARCHIVE="$PI_EVAL_ROOT/downloads/vale_3.24.0_Linux_64-bit.tar.gz" uv run --locked tools/check.py
SLOPSENTRAL_PI_PACKAGE="$PI_EVAL_ROOT/slopsentral" node --test tools/tests/pi-host.test.mjs
```

The source lock pins its own Pi compatibility target. To check the evaluation
runtime as well, install Pi 1.3.0 into that disposable dependency tree with
`npm install --no-save --package-lock=false --ignore-scripts @earendil-works/pi-coding-agent@1.3.0`,
then repeat the native loader tests. Preserve both results. The broader runtime
tests include process-group cleanup and need separate evidence on each platform.
The shell repository's full gate is `scripts/check.sh`; use its documented container
when its shell, editor, and process-isolation prerequisites are needed.

Prepare an independent fixture from Git bytes, with its receipt outside that workspace:

```sh
python3 packaging/cloud/prepare-fixture.py --repository "$PWD" --revision <pinned-sha> \
  --fixture static-callback-fixture --destination /workspace/participant-fixture \
  --receipt /workspace/evidence/fixture.json
python3 packaging/cloud/evaluate.py --plan /workspace/evidence/plan.json
```

The exporter admits only the three named public fixtures and source files. It
excludes evaluation reports, prior answers, and oracle files. The plan command
performs no inference. Keep plan, output, authentication, and model storage outside
the participant workspace. The exact eleven-sample evaluation plan is a separate
input; this setup does not invent or replace it. `--execute` remains rejected on
Linux until imported-model and native-query qualification is complete. Never copy
personal `OAuth` files from a Mac.

Before model work, preserve the real registry report, the exact native declaration,
callers and qualified TRACE replies, imported-model readiness, and artifact identities.
Run the existing SDK compatibility check separately from the pure tests:

```sh
node experiments/host-observation/pi_evaluation_sdk_check.mjs \
  "$PI_EVAL_ROOT/npm/node_modules/@earendil-works/pi-coding-agent" \
  /absolute/path/to/actual/extension.ts <adapter-sha256>
```

That check doubles provider transports and uses synthetic credentials. It proves
SDK compatibility only. It performs no native semantic query or model inference.
Any failure blocks claims about that boundary.
The authored RPC extension requires the actual canonical installed command before
loading. Session-admission failures must be reported before policy assertions;
an extension-load failure does not establish an SDK budget defect.

Build a Linux Control archive with `assembleKastControlDist` and an explicit numeric
`-PcontrolVersion`. Build the matching Host release with `generateHostReleaseRecord`
and `-PhostedIdeaHome` pointing to the pinned IDE. Semantic modules still resolve
their pinned Platform SDK dependencies. Keep official SDK access and Host artifacts
as explicit prerequisites; do not substitute an unrelated release or mock plugin.

## Disposable Linux prerequisites

```sh
python3 packaging/cloud/container.py
```

The `Dockerfile` pins its Debian base by digest and installs graphical, shell, and
native build prerequisites. OS packages follow Debian's repository; keep the
resulting image digest and package inventory. The helper uses the approved proxy
and its resolved host name mapping for build children. It selects the managed local
Docker daemon and preserves inherited registry configuration and credentials.
Supply the platform's public CA through a read-only mount
when running a new container; pass only required proxy and certificate settings.
Never mount personal HOME, `OAuth` files, or the Docker socket into a test container.

The smallest graphical probe uses `Xvfb`, `Xauth`, font libraries, and the bundled JBR.
A later graphical IDEA experiment can use `xvfb-run -a -s "-screen 0 1280x720x24 -nolisten tcp"`.
Label that a graphical session under a virtual display. License, privacy, and trust
choices still require their normal approvals; this setup accepts none of them.
A successful graphical startup alone does not prove import or Kast readiness.

Use the bounded vendor launcher probe in an owned directory outside the checkout:

```sh
python3 packaging/cloud/probe-idea.py --idea-home "$PI_EVAL_ROOT/idea-2026.2.2" \
  --root /workspace/evidence/idea-headless --mode headless
python3 packaging/cloud/probe-idea.py --idea-home "$PI_EVAL_ROOT/idea-2026.2.2" \
  --root /workspace/evidence/idea-graphical --mode virtual-display
```

Use `docker run --init` when the probe runs as the container entry point, so the
runtime reaps orphaned IDE and Xvfb children. The probe accepts no first-run choices,
installs no plugin, and records startup costs plus logs. A timeout or clean exit is a probe outcome, never semantic readiness.

Record bootstrap CPU, peak RAM, disk, and import cost separately from native query
work and provider latency. Compare correctness and work counts across machines;
raw elapsed times do not establish a cause for Mac-versus-cloud differences.

IntelliJ IDEA has a unified distribution since 2025.3; core Java and Kotlin
functionality remains free. The old Community Edition name is still present in
Kast's Maven SDK coordinates. Distribution identity, Kotlin plugin identity, and
bundled JBR must be checked against the current project contracts independently.
Do not replace JBR or rewrite distribution metadata to pass admission.

- [JetBrains installation and runtime requirements](https://www.jetbrains.com/help/idea/installation-guide.html)
- [Cloud environment publication](https://learn.chatgpt.com/docs/environments/cloud-environments)

Changes inside one cloud task do not update the saved environment. An environment
owner must set its install command to the bootstrap above, test the setup, and
republish it. Selecting or modifying a task container is not publication.
