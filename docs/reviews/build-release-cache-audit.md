# Build and release reuse audit

Reviewed 2026-09-11 while preparing the plugin-native change release. This is a
bounded configuration review; artifact promotion and SDK extraction redesign are
follow-up work.

## Observed baseline

- [CI](../../.github/workflows/ci.yml) runs `productBuildGate`, which already
  produces control and semantic-runtime archives. The latest main
  [successful run](https://github.com/amichne/kast/actions/runs/34605367304)
  retained a roughly 516 KB report artifact and no distribution artifact.
- [Release](../../.github/workflows/release.yml) resolves a stable version, admits
  the exact current main SHA, builds and checks the candidate, then publishes its
  archives, schema, module knowledge, SBOM and individual checksums. The last
  [successful release run](https://github.com/amichne/kast/actions/runs/34582197967)
  restored Gradle dependency/build caches and reported 31 tasks from cache out of
  254 actionable tasks. Its build took 10 minutes 25 seconds. This one run is an
  observation, not a cache performance benchmark.
- [Gradle properties](../../gradle.properties) enable the build cache,
  configuration cache and parallel execution. Both workflows use the same pinned
  Gradle setup action and Python dependency lock input. Cache cleanup uses the
  action's `on-success` default.
- The repository cache API reported 25 entries totaling 14,410,231,441 bytes.
  Separate PR entries included approximately 1.85 GB dependency caches and
  1.39 GB build caches. CI explicitly allowed same-repository PRs to write caches;
  the pinned action defaults to writes on the default branch only.
- IDEA extraction is already an input-keyed
  [cacheable task](../../build-logic/src/main/kotlin/support/tasks/ExtractIdeaDistributionTask.kt).
  Multiple owners materialize separate SDK directories. The observed release
  restored some extraction tasks from Gradle's cache. Copying every SDK directory
  into the Actions cache would add substantial duplicate data.

## Small changes applied

1. CI uses the Gradle setup action's default cache write policy. Main continues
   to populate caches; PRs and other branches restore them without producing
   additional branch cache copies. Existing entries are left to normal retention
   and eviction.
2. Successful main CI retains its already-built control and semantic-runtime
   archives as `ci-distributions-<source SHA>` for seven days. This adds no build
   task. The CI artifact does not include a hosted plugin or claim native runtime
   qualification.
3. Release retains `release-candidate-v<version>-<source SHA>` for seven days
   after the candidate build succeeds and before publication. This preserves the
   exact asset set if publication fails. Already compressed archives use upload
   compression level zero. Publication still performs its existing source,
   version and checksum admission.

The job graph remains one CI job and one independent release job. Their build,
verification and publication commands are unchanged. Added upload steps carry
existing outputs; their remote duration has not yet been measured. The local
`actionlint` and routine-gate checks verify syntax and preserve the required
proof task set; they do not prove a future cache hit or successful artifact upload.

## Reuse procedure

Use the successful run's exact SHA and artifact name when downloading with
`gh run download <run-id> --name <artifact-name> --dir <new-directory>`.
Inspect the run status and head SHA first. CI distributions support inspection
and matching-source packaging investigations. Full native plugin qualification
requires the separately matched plugin artifact as well.

For a retained release candidate, verify the individual `.sha256` files and SBOM
source revision, version and asset inventory before reuse. Published releases
already retain the same asset set permanently. Download the exact release when
reproducing its installation or compatibility behavior. A cache hit or an archive
name alone is not release approval.

## Follow-up backlog

| ID | Gap and next decision | Scope / completion evidence |
| --- | --- | --- |
| BUILD-REUSE-1 | CI archives embed the development version; release selects a new stable version and requires current main. Direct promotion needs an explicit source/version/artifact contract. | Separate delivery change. Require a matched plugin, manifest and checksum admission, required checks for the exact source, native qualification, and a clear publication retry policy. Do not relabel an old archive. |
| BUILD-CACHE-1 | Project configuration-cache state is not restored between Actions jobs because no cache encryption key is supplied. | Optional enablement after selecting secret ownership and measuring configuration cost. Use the action's encrypted configuration-cache support; never cache unencrypted project state. |
| BUILD-CACHE-2 | Owners extract several copies of the same pinned IDEA SDK. Gradle caches the task output, but each owner still materializes a copy. | Separate build-logic investigation. Measure extraction, snapshotting, restore size and wall time before considering shared immutable SDK ownership. Keep the existing content inputs and integrity checks. |
| BUILD-CACHE-3 | Cache footprint is large and the observed release reused only a fraction of task outputs. | Observe the next main CI and release after the write-policy change. Compare cache bytes, restore/save time and task outcomes; investigate misses before broadening cached paths. |

Sources for action behavior:
[pinned Gradle setup inputs](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/setup-gradle/action.yml),
[GitHub cache scope](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching),
and [artifact retention and retrieval](https://docs.github.com/en/actions/tutorials/store-and-share-data).
