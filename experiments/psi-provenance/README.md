---
type: reference
title: Local PSI provenance prototype
description: Exact-build experiment for preserving content causes through unloaded PSI invalidation.
resource: experiments/psi-provenance
tags: [psi, provenance, experiment]
timestamp: 2026-11-13
source_revision: 761140b84a02794589a4f07c87477f4b71476532
code_sources:
  - experiments/psi-provenance/PsiContentProvenance.java
  - experiments/psi-provenance/ProvenancePatch.java
  - experiments/psi-provenance/prototype
  - experiments/psi-provenance/test/io/github/amichne/kast/runtime/hosted/NativePsiProvenanceTest.java
---

# Local PSI provenance prototype

The local prototype preserves a retained query result after an unloaded README content change on IDEA 262.10315.125.
Source changes and unattributed PSI changes still invalidate the result.
This experiment does not change Kast's production dependency or the global installation.

## Platform changes

`FileDocumentManagerImpl.contentsChanged` passes its actual `VFileContentChangeEvent` through a new listener callback and its EDT bridge.
The default callback preserves existing subscribers by calling `fileWithNoDocumentChanged`.
The project listener takes the new path only when the file has no cached PSI view provider.
Loaded PSI retains its existing behavior.

`PsiVFSListener` emits a separate cause-bearing event for each eligible content contribution.
It neither consumes nor resets the existing unloaded-PSI coalescing flag.
This preserves a later source contribution in the same batch.
Existing notifications carry `Unattributed`; only the new content path constructs `Content`.

`PsiModificationTrackerImpl` owns an independent counter.
Content within source or library roots advances that counter.
Content outside those roots leaves it unchanged.
Invalid files, disposed projects, directory inputs, and unattributed changes advance it conservatively.
The counter updates before listeners receive the notification.
The original global and language trackers still advance on those events.
The experimental Kast adapter reads the new counter without subtracting from the global count.

Construction trusts the patched Platform dispatch path, not arbitrary plugins.
The cause carries the contributing file only for synchronous processing under write access.
It is not a durable event history or proof of freshness after that processing ends.

## Build an isolated copy

The builder checks eight exact Platform class digests and the Kast adapter class digest.
Different class bytes reject the build.
It copies the SDK before replacing entries in the owning JARs.
It does not install the copy, patch the input SDK, or change plugin caches.
The output must be a new directory disjoint from the input SDK.

The following invocation built the full SDK used for native checks:

```sh
mise exec -- uv run --locked --script experiments/psi-provenance/prototype \
  --idea-home '/private/tmp/kast-psi-donor/IntelliJ IDEA.app/Contents' \
  --output /private/tmp/kast-psi-provenance-final-sdk \
  --workspace-jar workspace/intellij-read/build/libs/workspace-intellij-read-0.51.3-9-g761140b84.jar
```

`receipt.json` records input and output archive digests and replaced entries.
`platform-inputs.txt` records the checked Platform class digests.
The output also contains the patched adapter as `workspace-intellij-read.jar`.
No production archive or manifest is issued.

## Native checks

The init script aligns the fixture SDK and test-framework dependencies to 262.10315.125.
It preserves the exact-build guard and reads the original macOS product metadata under `Resources`.
It selects the patched owning JARs ahead of original compile dependencies.
It uses the complete vendor SDK; the reduced managed Host is not a fixture SDK.

```sh
KAST_SEMANTIC_FIXTURES=true mise exec -- ./gradlew \
  -I experiments/psi-provenance/native-tests.init.gradle \
  :runtime:hosted:nativeFixtureTest --tests '*NativePsiProvenanceTest' \
  -PnativeFixtureIdeaHome=/private/tmp/kast-psi-provenance-final-sdk/idea \
  --console=plain
```

These dependencies differ from the repository's fixture pin.
The measured run used a separate local checksum set; the checked-in verification metadata was restored byte-for-byte afterward.
Without that local set, strict dependency verification rejects the command before tests run.
Generating checksums does not establish publication authority or independent artifact trust.

Eleven native cases passed without mocked PSI, indexes, or counters.
They cover README retention, both mixed-batch orders, source document edits, source ownership independent of extension, and counter publication order.
They also cover legacy callbacks and standalone or nested cache drops.
Library source and class roots outside project content both preserve invalidation for actual content writes without cached PSI.
Their observers see the counter advance before notification, even for Markdown files.
Standalone forced reload removes a cached README view provider.
It emits a children-change notification without a file attachment through its cached parent directory.
A forced reload nested directly inside another README content callback also advances the counter.
The outer README event leaves that counter unchanged before the nested reload begins.
Mixed-batch cases supply content events to the real refresh processor; they do not claim an external watcher produced that batch.
Restoring the original publisher jar made the README case fail with a counter increase from 16 to 17.
The other five cases present in that run passed.

## Independent-project result

The private installation used Control and Host artifacts from main revision `761140b84a02794589a4f07c87477f4b71476532`.
Its separate `amichne/konditional` checkout used revision `f191bc264fb18b65d54f228233d7630589fbaf37`.
Only its private Platform JARs and workspace adapter were replaced for this experiment.
The adapter also included the existing local VFS refinement and bounded PSI diagnostics.

The real public CLI found `BucketInfo` and retained its result.
A no-edit control and an external README edit both preserved epoch 1 on the same Host.
An external Kotlin comment edit rejected the retained result with `result-unavailable`.
A fresh query succeeded at epoch 2.
The original Platform JARs, adapter JARs, and project files were restored after the Host stopped.
Global launcher and management-record digests remained unchanged.

Raw receipts remain under `.amp/in/psi-provenance/` in the validation checkout.
This is local prototype evidence on macOS arm64, not release qualification, a supported vendor patch, or a claim about other IDEA builds.
Archive replacement, forced reload without a cached parent directory, structural VFS changes, and the complete product acceptance matrix remain outside the measured cases.
