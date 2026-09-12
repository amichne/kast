# Installed knowledge bundle

## Delivered scope

The installed control product carries Kast's own public Kotlin declaration
headers, source KDoc and tracked `AGENTS.md` guides. Run these commands from any
directory after installation:

```shell
kast knowledge KastCli
kast knowledge manifest.json
kast knowledge modules/cli/index.json
kast knowledge guides/root.json
```

Pass a search item's `resource` value to `kast knowledge` to read its declaration
card. Pass a card's `governingGuides` resource to read the complete guide. The
single selector accepts either search text or a resource path; there is no
`--resource` option.

Lookup reads the installed control root discovered from the CLI's code source.
It requires no checkout, open IDE, semantic worker, Gradle invocation or network
lookup. The bundle describes the Kast version being shipped, not the caller's
current project.

## Build authority

`generateKastDocumentation` launches the pinned Kotlin PSI parser in the existing
isolated compiler process. Its typed output is either `complete`, containing
syntax evidence and declarations, or `rejected`, containing finite source
failures. Rejection never publishes a partial declaration inventory.

`generateInstalledKnowledgeBundle` depends on `verifyKastArchitecture` and uses
that task's verified module inventory. It assigns each source to the unique
longest owning module directory and joins tracked guide content by source path.
A descendant guide governs declarations under its directory; it never becomes
a guide for the entire parent module. This retains the scope used by the
existing module-knowledge projection without introducing another module graph.

The bundle records `KOTLIN_PSI_SYNTAX` and these explicit limitations:

- `KOTLIN_SOURCE_ONLY`
- `NAMED_DECLARATIONS_ONLY`
- `NO_TYPE_RESOLUTION`
- `NO_INHERITED_DOCUMENTATION`

Supported named declarations are classes, interfaces, enums and entries,
annotation classes, objects, functions, properties and type aliases. Private or
internal declarations, declarations inside hidden owners, and local declarations
are excluded. Constructor and accessor declarations are not separate cards.
Headers retain source spelling, constraints, annotations, constructor visibility
and literal whitespace. Function and property implementation bodies are omitted;
inferred types and inherited documentation are not manufactured.

## Installed resources

The existing `stageKastControlProduct` task stages the bundle under
`share/kast/knowledge/`:

```text
manifest.json
modules/<project-path>/index.json
modules/<project-path>/declarations/<sha256-id>.json
guides/root.json
guides/<guide-directory>.json
```

The manifest lists module resources and guide references. Module indexes contain
shallow declaration descriptors and module-level guide references. Declaration
cards contain their source path, exact declaration header, KDoc and governing
guide resources. Guide resources contain complete `AGENTS.md` content and its
SHA-256. Search reads descriptors only and returns at most 20 matches.

## Admission and bounds

Generation rejects duplicate identities or resource paths, unsupported source,
missing or incorrectly scoped guides, ambiguous module ownership and oversized
resources. Publication occurs only after the complete projection passes.

The installed reader decodes the exact resource DTO and checks schema version,
evidence, inventory membership, module ownership, declaration identity, guide
scope and content hashes. Unknown fields, unsupported evidence, unlisted
resources, parent traversal, symbolic links and malformed documents reject with
finite diagnostic codes. Reads and generation share an 8 MiB per-resource
limit, 512 modules, 4,096 guides and 20,000 declarations per module. Selectors
are limited to 4,096 UTF-8 bytes and exclude control characters.

## Verification

```shell
./gradlew :build-logic:test :cli:check verifyJsonContracts installedProductTest
./gradlew productBuildGate
./gradlew knowledgeImpact verifyKnowledgeBase
```

Focused tests cover deterministic projection, shallow indexes, complete KDoc,
source-header preservation, nested guide scope, finite extraction failures and
malformed installed resources. Installed acceptance checks the archive and runs
search followed by an exact card read in an unrelated directory with a private
home, a restricted tool path and closed proxy settings. The proxy policy is not
kernel-level network isolation; the lookup implementation has only local file
effects.

External dependency documentation, Java/Javadoc, Dokka, compiler/K2 resolution,
inherited documentation, semantic usage edges and search acceleration remain
outside this iteration.
