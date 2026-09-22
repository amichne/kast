# Configuration and retirement testing verification

Initial implementation reviewed against `8e5cbe01d47efd034122166d12ee4d67ba87682d`;
the PR incorporates main through `eae2c10d6634dc6c308921b1cb6e2c6f3df81c19`.
The [development guide](../development.md#test-one-behavior-at-a-time) owns the
general workflow, with repository policy in [AGENTS.md](../../AGENTS.md#testing).
These are ordinary unittest/JUnit cases using production rules. Dependency budgets
describe the operation under test, excluding the runner and compilation environment.
Gradle still requires a JVM and may need to provision declared build dependencies.

## Migration inventory

| Assertion and owner | Action | Behavior dependencies and retained evidence |
| --- | --- | --- |
| `distribution:contract` — `KastConfigurationResolutionTest`, `SavedConfigurationDocumentTest` | Retain | Explicit maps/bytes and production types; no filesystem, process, socket, clock or network. Retains precedence, selected value, provenance, malformed selection rejection, duplicate/unknown key rejection and document bounds. |
| `app-server` — ordinary-file cases in `SavedConfigurationIngressTest` | Retain | One private root, only selected/adversarial files. Retains absent-selector/default provenance, symlink/missing-file rejection, workspace ancestor rejection and literal shell expressions remaining data. No version tree. |
| `app-server` — combined current-alias case | Split | Four independent roots: valid ownership, held lock, callback-triggered alias replacement and missing manifest. Only configuration, current link, manifest and activation lock. Real canonical/no-follow/identity admission remains authoritative; replacement retains typed `CHANGED_DURING_READ`. No preceding successful case, race or sleep. |
| `packaging` — retirement outcome/event assertions | Move downward | `RetirementBoundaryTest` requires one exact immutable request and scripted child observation. Zero installations, files, children or clocks. Both child stages retain ordered started/result events, bounded event fields and finite failures. Timeout and I/O cases add coverage at this seam. |
| `packaging` — child invocation | Binding check | `RetirementAdapterTest` uses one private root and one trivial executable to check exact arguments, cwd, explicit environment, EOF stdin and nonzero status. Exception-translation cases script `subprocess.run` without waiting. |
| `packaging` — ownership and protected state in `LifecycleTest` | Retain | Existing synthetic installation/process composition. Failure keeps configuration, epoch, payload, manifest and workspace; transition evidence may be written. Worker receipts still block cleanup after child success. Hosted installations still omit legacy workspace stop. |
| `packaging` — foreign-command collision | Retain, tighten fixture | One private tree and one admitted Bash interpreter. This regular-file branch needs only built-ins. Private PATH contains failing recording sentinels, no real utility fallback. Requires collision reason/path, identical foreign bytes, no forbidden invocation and no installation payload. |

The retirement production wrapper snapshots arguments/environment, selects the OS
executor and retains the existing 60-second timeout and discarded child streams.
The inner rule requires its executor and observation sink. The script records
requests and keeps violations separately, so a caught fixture assertion cannot
be mistaken for an expected product rejection. A child exit of zero proves only
that attempt; receipt and installation ownership checks still own cleanup admission.

The former success-event test built an installation and launched a lifecycle
interpreter plus two retirement children. Its event assertions now launch zero
processes and allocate no filesystem state. Alias cases retain the filesystem
boundary but no longer mutate one shared fixture through four behaviors. The
collision case retains one Bash invocation and removes the wholesale host PATH;
it never needed release assets, IDEA or an installation.

## Reproduce without native composition

Run from the repository root:

```sh
python3 packaging/test-installation-lifecycle.py RetirementBoundaryTest
python3 packaging/test-installation-lifecycle.py RetirementAdapterTest
python3 packaging/test-installation-lifecycle.py LifecycleTest.test_failed_retirement_preserves_state
python3 packaging/test-installer-entrypoint.py InstallerEntrypointTest.test_noninteractive_collision_fails_closed_at_selected_command_directory

./gradlew :distribution:contract:test \
  --tests 'io.github.amichne.kast.distribution.contract.configuration.KastConfigurationResolutionTest' \
  --tests 'io.github.amichne.kast.distribution.contract.configuration.SavedConfigurationDocumentTest'
./gradlew :app-server:test \
  --tests 'io.github.amichne.kast.appserver.SavedConfigurationIngressTest'

python3 packaging/test-installation-lifecycle.py
python3 packaging/test-installer-entrypoint.py
./gradlew verifyJsonContracts verifyConfigurationIngress knowledgeImpact verifyKnowledgeBase
./gradlew :app-server:spotlessCheck :app-server:checkTestKotlinFileLength
```

Existing `installationLifecycleTest` and `installerEntrypointTest` Gradle tasks
invoke the same Python files. These tasks and the JUnit selectors do not depend on
release/product assembly, released-asset download, IDEA startup or native acceptance.
The full installer file retains its separate synthetic archive fixtures; only the
collision selector has the built-ins-only budget.

For order/contamination checks, unittest accepts repeated selectors, creating a
fresh instance each time. Each JUnit alias method receives a fresh `@TempDir`.
Temporary paths and filesystem identities need not repeat byte-for-byte.
Scripted observations establish policy handling, not that an OS/IDE produces
those observations under any particular real-world condition. The filesystem and
adapter checks establish only their respective private filesystem/process facts.

The controlled collision PATH is not a security sandbox: shell built-ins and
absolute executable paths are outside it. The reviewed early branch uses built-ins
for a regular foreign file and exits before utility/platform/IDE/download admission.
Sentinel invocation fails the fixture independently of the installer's exit status.

## Verification observed for this change

- Baseline lifecycle file: 19 tests passed before the production refactor.
- Final full Python files: lifecycle 22 tests and installer entrypoint 6 tests
  passed on Python 3.14.5. Repeated boundary and collision selectors passed with
  fresh instances. The boundary/adapter classes also passed on system Python 3.9.6.
- `/usr/bin/python3 packaging/test-installation-recovery.py`: all 17 tests passed,
  retaining coverage of the recovery consumer's bundled lifecycle implementation.
- JUnit selectors above: ingress 8, resolution 8 and document parsing 3 tests
  passed, with zero skips. Their Gradle `--dry-run` graph contains no product
  assembly or native acceptance task.
- After updating to main, listed guards passed: JSON contracts (1,034 fingerprints, zero violations),
  configuration ingress (zero findings), formatting, test file length and knowledge
  validation. Impact review covered distribution, operation outcomes, evidence authority
  and refinement; their existing claims remain accurate, so no concept refresh was needed.
- From `docs/public`, `npx --yes mint@4.2.841 validate` passed for the integrated
  public guidance. The focused Kotlin and full Python suites passed again after
  updating to main.
- `git diff --check` passed. Full repository/product gates, native IDEA/macOS
  composition, real timeout generation and release qualification were not run.

## Native boundary

The [development guide's native lifecycle section](../development.md#run-native-lifecycle-smoke)
designates [`run-hosted-lifecycle-smoke.py`](../../packaging/run-hosted-lifecycle-smoke.py)
and documents its staged inputs, actual assertions and limits. No native environment
was provisioned or run for this migration. Unrelated semantic/native coverage remains
unchanged.

## Follow-up observations outside this change

- The native lifecycle smoke has no direct Gradle wiring and does not qualify a
  fresh install or login service. Adding either is a separate scope decision.
- Kotlin compilation reports pre-existing warnings in `KastCodexMainLifecycleTest`,
  `NativePresentationEvidence`, `CompletedWithoutAgentMessagesSchema` and, after
  updating to main, `BrokerPublicEndpointTest`. The initial build also reported
  Gradle deprecations. No cleanup of those unrelated owners is included.
