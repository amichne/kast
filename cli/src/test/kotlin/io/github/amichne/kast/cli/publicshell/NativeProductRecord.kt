package io.github.amichne.kast.cli.publicshell

import kotlinx.serialization.Serializable

/** Synthetic boundary documents for native transport tests, never release-qualification evidence. */
@Serializable
internal data class NativeProductRecord(
    val schemaVersion: Int = 2,
    val repository: String = "amichne/kast",
    val sourceRevision: String = "a".repeat(40),
    val sourceTree: String = "b".repeat(40),
    val version: String = "0.0.1",
    val producer: NativeProductProducer = NativeProductProducer(".github/workflows/ci.yml"),
    val targets: List<NativeProductTarget> = listOf("linux-x86_64", "macos-aarch64").map(::NativeProductTarget),
)

@Serializable
internal data class NativeProductProducer(val workflow: String, val runId: Long = 1, val runAttempt: Int = 1)

@Serializable
internal data class NativeProductTarget(
    val target: String,
    val inputs: NativeProductInputs = NativeProductInputs(),
    val bundle: NativeProductAsset = NativeProductAsset("kast-composed-setup-v0.0.1-$target.tar.gz", "c".repeat(64)),
    val runtime: NativeProductAsset = NativeProductAsset("kast-runtime-v0.0.1-$target.zip", "d".repeat(64)),
    val qualification: NativeProductQualification =
        NativeProductQualification(
            NativeProductAsset("qualification-$target.json", "e".repeat(64)),
            bundleSha256 = bundle.sha256,
            runtimeSha256 = runtime.sha256,
        ),
)

@Serializable
internal data class NativeProductInputs(
    val javaReleaseSha256: String = "1".repeat(64),
    val dependencyLocksSha256: String = "2".repeat(64),
    val compositionRecipeSha256: String = "3".repeat(64),
    val donorSha256: String = "4".repeat(64),
)

@Serializable internal data class NativeProductAsset(val name: String, val sha256: String, val bytes: Long = 1)

@Serializable
internal data class NativeProductQualification(
    val receipt: NativeProductAsset,
    val producer: NativeProductProducer = NativeProductProducer(".github/workflows/composed-linux.yml"),
    val bundleSha256: String,
    val runtimeSha256: String,
    val controlSha256: String = "f".repeat(64),
    val kind: String = "NATIVE",
    val outcome: String = "COMPLETE",
)
