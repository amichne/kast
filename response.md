Now I have enough to give a thorough review. Here it is:

---

## CI & Smoke Review

### The core architectural problem

The `kast` module is the glue: it depends on both `:kast-cli` and `:backend-standalone` and produces the one portable zip that CI tests. This means the two units are **co-packaged and never independently validated**. Every smoke test exercises them as a single artifact. If you want atomic, independently releasable units, this coupling is the root issue to resolve — everything else follows from it.

---

### Flaws in the smoke scripts

**1. Installer smoke bypasses checksum verification (`smoke-installer.sh:99`)**

```python
"digest": "",
```

An empty string means the installer's SHA-256 verification path is never exercised. The smoke gives false confidence: it proves the install *flow* works but not the *security-critical* checksum path. The fix is to compute the actual SHA-256 of the test zip and populate `digest` before running the installer:

```python
import hashlib
digest = hashlib.sha256(asset_path.read_bytes()).hexdigest()
payload = { ..., "assets": [{ ..., "digest": f"sha256:{digest}" }] }
```

**2. Native binary is never tested in CI**

`ci.yml` builds `:kast:portableDistZip` but **GraalVM native compilation is not in the CI build** — it's only in `release.yml`. The native binary (`bin/kast`) is therefore absent from the portable zip that CI smokes. What CI actually validates is the JVM fallback path in the wrapper script. You can ship a broken native binary and CI will never catch it.

Mitigation options: add `:kast-cli:nativeCompile` to CI with `--no-fallback` disabled for speed, or have a separate CI job that at least confirms the native binary compiles and runs `--help`. The `kast` module's `syncPortableDist` already skips native if `jvmOnly` property is set — use that property explicitly in CI rather than implicitly relying on it.

**3. `analysis-server` is never independently smoked**

Every smoke call goes through the kast CLI → daemon subprocess path. There's zero validation that `backend-standalone` starts and responds to RPC in isolation. Since you want the server to be startable as a standalone background process, there should be a smoke that:

1. Invokes `backend-standalone --transport=unix-socket --socket-path=<tmp>` directly
2. Sends a `capabilities` RPC request against the socket
3. Asserts the response

Without this, the "decoupled runnable" story is untestable in CI.

**4. Rename smoke validates planning, not edit application**

The smoke asserts `rename["edits"]` contains the right files but never calls `kast apply-edits` to verify those edits land on disk correctly. The mutation path is the most dangerous — if the apply-edits command regresses, CI won't catch it.

**5. Stop-polling is fragile**

```bash
for _ in $(seq 1 30); do
  if ! find "$instance_dir" -name '*.json' -print -quit | grep -q .; then
    break
  fi
  sleep 1
done
```

This can either flake (daemon took 31 seconds to clean up) or silently pass when the daemon didn't actually stop (loop exits after 30s regardless). Replace the sleep loop with a proper wait — the `workspace stop` command should block until the descriptor is gone, or the smoke should fail immediately if descriptors remain after a timeout.

---

### Flaws in `ci.yml` structure

**6. Monolithic `cli-smoke` job**

All of these run in a single sequential job:
```
:analysis-api:test
:analysis-server:test
:backend-standalone:test
:backend-intellij:test
:backend-intellij:buildPlugin
:backend-intellij:verifyPluginStructure
:kast:test
:kast:portableDistZip
```

`backend-intellij:test` pulls the IntelliJ distribution and takes significant time. A `backend-intellij` test failure blocks the CLI smoke entirely. These should be split into independent parallel jobs with explicit `needs:` wiring:

```
┌─ test-analysis-api ─────────┐
│                              ├─> smoke-kast-cli
├─ test-analysis-server ──────┤
│                              │
├─ build-intellij-plugin ─────┘

└─ eval-agent-routing (needs: smoke-kast-cli)
```

**7. GraalVM setup is wasted for the JVM-only smoke**

CI sets up `graalvm/setup-graalvm` then builds a portable zip without the native binary (because `nativeCompile` isn't in the task list). GraalVM setup adds minutes for no benefit. Use `actions/setup-java` with Temurin in CI and reserve GraalVM for the release workflow.

**8. `eval-agent-routing` builds `kast` redundantly**

The eval job runs `./gradlew :kast:installDist` after `cli-smoke` already built the full portable zip. If the eval job received the built distribution as an artifact from `cli-smoke`, you'd avoid rebuilding from scratch.

---

### What true atomic releases require

| Unit | Today | Needed |
|------|-------|--------|
| `kast-cli` | Co-packaged with `backend-standalone` via `kast` module | Own release zip (native binary + embedded skill). Needs a way to locate/start a separately-installed backend. |
| `analysis-server` | Bundled inside `kast` distribution; never independently released | Own `backend-standalone-<version>-<platform>.zip` release asset. Own smoke. Own install path in `install.sh`. |
| `analysis-api` | `kast.kotlin-library` plugin only — no `maven-publish` visible in build file | Apply `maven-publish`, publish to GitHub Packages or Maven Central. |
| `backend-intellij` | Already effectively atomic (own zip) | Already correct. Verify the plugin zip release is independently installable via JetBrains toolbox/settings without `kast-cli` present. |

The `kast` module today acts as a meta-package. To decouple:

1. **`kast-cli` release**: Drop the `:backend-standalone` dependency from the `kast` module (or create a new `kast-cli-dist` module). The CLI already supports `internalDaemonRunner = null` and can discover an external daemon. Make the external backend path first-class rather than an afterthought.

2. **`backend-standalone` release**: Add its own release job to `release.yml` (parallel with the CLI builds). Add a standalone smoke to `ci.yml`. The installer already has the infrastructure for multiple assets — extend it to handle `--components=standalone` independently.

3. **`analysis-api` Maven publish**: Add `maven-publish` to the `kast.kotlin-library` plugin or specifically to `analysis-api/build.gradle.kts`. Coordinate versioning so the API version is independently consumable by plugin authors.

The most impactful single change is **splitting `kast` module's dependencies** so `kast-cli` can be built and distributed without `backend-standalone`. That one structural change unlocks independent versioning, independent smoke testing, and independent release for both units.