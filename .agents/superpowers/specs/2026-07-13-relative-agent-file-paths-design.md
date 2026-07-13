# Relative Agent File Paths Design

Date: 2026-07-13

Issue: [#341](https://github.com/amichne/kast/issues/341)

Status: Approved for autonomous implementation

## Objective

Let typed agent commands accept repository-relative Kotlin target paths when
the caller declares an explicit workspace root. Convert every accepted target
to one canonical, workspace-contained absolute path before constructing any
backend request or mutation plan.

The defining regression is `kast agent diagnostics --workspace-root "$PWD"
--file-path cli/src/main/kotlin/...`, which currently spends a semantic-check
cycle only to have the backend reject the relative path as non-absolute.

## Requirements

1. Diagnostics `--file-path`, add-file `--file-path`, and mutation
   `--inside-file` accept absolute paths or paths relative to an explicit
   `--workspace-root`.
2. Every semantic target becomes one canonical absolute path before JSON-RPC
   request or mutation-plan construction.
3. Canonical targets must remain contained by the canonical workspace root.
4. Lexical escapes, symlink escapes, broken symlinks, unreadable path prefixes,
   directories, special files, and extensions other than `.kt` or `.kts` fail
   closed with a structured agent error before backend dispatch.
5. Existing files and safe in-workspace symlinks resolve to their real paths.
   A missing Kotlin leaf remains valid so diagnostics can refresh a deleted
   file and add-file can plan a new target.
6. Diagnostics output explicitly reports the canonical paths used for both
   refresh and analysis. Mutation plans report canonical paths in their typed
   request parameters.
7. Multiple targets retain caller order, paths containing spaces remain one
   argument, and existing absolute in-workspace paths remain compatible.
8. Installed agent guidance prefers concise workspace-relative examples where
   an explicit workspace root already establishes the base.

## Considered Approaches

### Normalize in every backend

Each backend could join relative paths to its workspace. This duplicates
security-sensitive behavior across IDEA and headless hosts, lets mutation plans
display a different path than the path eventually used, and leaves CLI output
unable to state the canonical target before dispatch.

### Perform lexical normalization in the CLI

The CLI could remove `.` and `..` segments without filesystem resolution. This
is fast but does not detect symlink escapes or distinguish a missing Kotlin
target from a directory or special file.

### Parse once into typed canonical paths

The selected approach adds a Rust agent-boundary parser that produces typed
canonical workspace roots and Kotlin target paths. It resolves the deepest
existing ancestor, checks the resolved target against the canonical workspace,
and only then converts the trusted path to the RPC string used by every step.
This centralizes the invariant, supports deleted targets, and makes request and
output identity agree.

### Revalidate pathname containment at the backend write seam

Canonical CLI paths still cross a process boundary before the backend mutates
the filesystem. An accepted ancestor or final entry can therefore be replaced
after CLI parsing or server validation. The selected implementation repeats
traversal in the shared `backend-idea` mutation implementation with POSIX
directory descriptors. The IDEA plugin uses that implementation directly;
`backend-headless` exposes the same mutation capabilities through
`KastIdeaBackendRuntime` and `KastPluginBackend`, so both hosts are in scope.
Every path component is opened without following symlinks. Existing
replace/delete
targets are atomically detached to a randomized quarantine with macOS
`renameatx_np(RENAME_EXCL)` or Linux `renameat2(RENAME_NOREPLACE)`, then hashed
and inspected with `fstat` through the same held descriptor. The open is
nonblocking and only a regular-file mode may proceed to hashing; FIFOs and
devices fail closed. Prepared commits, restoration, and recovery never
overwrite a concurrent final entry. Preparation/native commit failure restores
the original before prepared cleanup. A late replacement of a deletion
reservation is restored to the final name with no-replace and reported as a
typed conflict rather than displaced as cleanup. Cleanup
moves candidates behind randomized internal names and device/inode-checks them
immediately before unlinking. Cleanup refusal retains a recovery path, and a
replacement or deletion that already committed is reported as applied with
that evidence. IDEA records the typed commit before any later Document/VFS
work, hash validation, or post-write verification, so later failures retain
the committed path in partial-apply evidence. This is a separate
write-boundary guarantee; it does not change
request or rendered path identity.

The descriptor proves detached file identity; the held parent descriptor and
no-replace operations govern namespace transitions. Because POSIX rename and
unlink remain name-based, this protocol is not a directory lock or a general
transaction against a process with write permission that deliberately races
Kast's randomized internal names. Such authority is governed by directory
permissions. Changes made after the operation returns are also outside the
guarantee.

## Path Contract

The parser receives the declared `AgentRuntimeArgs` and one semantic target.
It first resolves and canonicalizes the effective workspace root. Relative
targets require `AgentRuntimeArgs.workspace_root` to be present; an inferred
current workspace is insufficient because the caller has not declared the
base. Absolute targets continue to work with either an explicit or inferred
workspace, but their resolved path must be contained by that workspace.

The parser lexically removes `.` and resolves `..` before touching the
filesystem. A relative target that leaves the declared root is rejected. It
then walks upward to the deepest existing path component and canonicalizes
that prefix:

- if the complete target exists, it must resolve to a regular `.kt` or `.kts`
  file inside the canonical workspace;
- if the leaf is missing, the deepest existing prefix must resolve to a
  directory inside the workspace, and the normalized missing suffix is
  appended to that canonical prefix;
- if an existing or broken symlink cannot be resolved safely, or resolves
  outside the workspace, the target is rejected;
- if metadata cannot be read, the command fails instead of guessing.

Safe symlinks that resolve within the workspace are accepted and the real
target is reported. Missing `.kt` and `.kts` leaves are accepted because a
deleted-file refresh and a new-file mutation are both meaningful typed
operations. Missing paths with unsupported extensions are rejected before
backend dispatch.

## Command Surface

The path parser applies to semantic target arguments:

| Command | Target argument | Canonical request field |
| --- | --- | --- |
| `kast agent diagnostics` | repeatable `--file-path` | `filePaths[]` for refresh and diagnostics |
| `kast agent add-file` | `--file-path` | `filePath` |
| `kast agent add-declaration` | `--inside-file` | `placement.scope.insideFile` |
| `kast agent add-implementation` | `--inside-file` | `placement.scope.insideFile` |

`--file-hint` remains a compiler-identity refinement rather than a target path;
canonicalizing it would destroy its current basename and partial-match
semantics. `--content-file` remains a payload-source argument and is not a
semantic workspace target. Scope-identity mutations and symbol identities do
not change.

Diagnostics adds command-level `filePaths` to `KAST_AGENT_COMMAND`. The list is
the same canonical list used to build both backend steps, so JSON, TOON, and
human rendering do not depend on a backend echo to identify the analyzed
files. Existing semantic completeness evidence continues to validate backend
file statuses against the canonical request paths.

## Error Contract

Path failures use structured agent envelopes and exit status one. Stable error
codes distinguish missing explicit context, invalid workspace roots, lexical
workspace escapes, unsafe symlink resolution, unsupported Kotlin target kinds,
and unreadable filesystem state. Error details include the original input and
the canonical workspace root when available. No backend session starts after a
path-validation error.

## Testing

Unit tests at the Rust path boundary cover relative and absolute files, lexical
escapes, safe and escaping symlinks, broken symlinks, deleted files, spaces,
unsupported extensions, directories, and special filesystem errors that can be
constructed portably.

Fake-daemon integration tests prove that:

- multiple relative diagnostics targets become ordered canonical request
  paths in both refresh and diagnostics calls;
- JSON, TOON, and human output expose the canonical paths;
- a deleted relative `.kt` path still reaches workspace refresh and then fails
  or succeeds according to typed backend completeness evidence;
- invalid targets fail before any backend request;
- existing absolute paths keep their prior request identity;
- add-file and file-scoped mutation plans contain canonical target paths.

IDEA backend tests deterministically replace a validated ancestor with an
escaping symlink immediately before add-file creation and file-scoped text
replacement. Both operations must return `UNSAFE_WORKSPACE_MUTATION` without
touching either the outside target or the displaced in-workspace directory.
Separate post-detach tests create a concurrent final entry after the original
inode has been hashed and `fstat`-inspected. Replacement and deletion must
leave the concurrent entry untouched and either restore the validated inode or
report its quarantine recovery path through typed `CONFLICT`. The same suite
proves create never cleans a concurrent final entry by name, missing edit
targets map to `NOT_FOUND`, original permissions come from the hashed inode,
rollback restoration precedes fallible prepared cleanup, and committed
cleanup failures retain deterministic recovery evidence without being
reported as unapplied. Additional seams prove preparation/native-rename
rollback, late deletion-reservation replacement, nonblocking FIFO rejection,
and applied ledgers surviving post-commit create/delete/text and later hash
failures. Normal create, VFS document synchronization, and delete behavior
remain intact.

Packaged-content tests pin the concise relative guidance. Final validation runs
the locked Rust suite, formatting, Clippy with warnings denied, release
contract checks, docs contracts, and diff hygiene.

## Non-Goals

- changing `--file-hint` resolution or exact symbol lookup;
- constraining mutation `--content-file` payloads to the workspace;
- changing backend absolute-path request contracts;
- making diagnostics treat a deleted file as semantically analyzed;
- resolving runtime/worktree readiness or backend attachment;
- restoring raw or arbitrary JSON-RPC command surfaces.
