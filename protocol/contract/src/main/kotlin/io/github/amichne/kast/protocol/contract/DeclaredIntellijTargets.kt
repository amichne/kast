package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

enum class IntellijTargetFailure {
    RESOURCE_UNAVAILABLE,
    INVALID_REGISTRY,
    UNSUPPORTED_SCHEMA,
    DUPLICATE_TARGET,
    TARGET_IDENTITY_MISMATCH,
    DEFAULT_TARGET_UNAVAILABLE,
    INVALID_IDE_BUILD,
    TARGET_UNAVAILABLE,
}

/** A source-declared compile target. This fact alone does not qualify a live semantic operation. */
class DeclaredIntellijTarget
internal constructor(
    val releaseLine: IdeReleaseLine,
    val ideBuild: IdeBuildIdentity,
    val kotlinPluginBuild: KotlinPluginBuildIdentity,
)

/** Immutable packaged target authority, independent of whichever target compiled this consumer. */
class DeclaredIntellijTargets
private constructor(
    val defaultTarget: DeclaredIntellijTarget,
    targets: List<DeclaredIntellijTarget>,
) {
    val targets: List<DeclaredIntellijTarget> = java.util.List.copyOf(targets)

    fun forReleaseLine(line: String): Refinement<DeclaredIntellijTarget, IntellijTargetFailure> =
        targets.singleOrNull { it.releaseLine.value == line }?.let { Refinement.Refined(it) }
            ?: Refinement.Rejected(IntellijTargetFailure.TARGET_UNAVAILABLE)

    /** Vendor launch metadata historically permits a two-part build; actual Host identity is admitted separately. */
    fun forVendorBuild(build: String): Refinement<DeclaredIntellijTarget, IntellijTargetFailure> =
        if (!build.matches(VENDOR_BUILD)) Refinement.Rejected(IntellijTargetFailure.INVALID_IDE_BUILD)
        else forReleaseLine(build.substringBefore('.'))

    companion object {
        private const val MAXIMUM_BYTES = 65_536
        private val VENDOR_BUILD = Regex("[0-9]{3}\\.[0-9]+(?:\\.[0-9]+)*")
        private val json = Json

        val packaged: Refinement<DeclaredIntellijTargets, IntellijTargetFailure> by lazy {
            try {
                val input =
                    DeclaredIntellijTargets::class.java.getResourceAsStream("/intellij-targets.json")
                        ?: return@lazy Refinement.Rejected(IntellijTargetFailure.RESOURCE_UNAVAILABLE)
                val bytes = input.use { it.readNBytes(MAXIMUM_BYTES + 1) }
                if (bytes.size > MAXIMUM_BYTES) Refinement.Rejected(IntellijTargetFailure.INVALID_REGISTRY)
                else decode(bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (_: IOException) {
                Refinement.Rejected(IntellijTargetFailure.RESOURCE_UNAVAILABLE)
            } catch (_: CharacterCodingException) {
                Refinement.Rejected(IntellijTargetFailure.INVALID_REGISTRY)
            }
        }

        fun decode(encoded: String): Refinement<DeclaredIntellijTargets, IntellijTargetFailure> {
            if (encoded.length > MAXIMUM_BYTES || encoded.encodeToByteArray().size > MAXIMUM_BYTES)
                return Refinement.Rejected(IntellijTargetFailure.INVALID_REGISTRY)
            val document =
                try {
                    json.decodeFromString<IntellijTargetsDocument>(encoded)
                } catch (_: SerializationException) {
                    return Refinement.Rejected(IntellijTargetFailure.INVALID_REGISTRY)
                }
            if (document.schemaVersion != 1) return Refinement.Rejected(IntellijTargetFailure.UNSUPPORTED_SCHEMA)
            if (document.targets.isEmpty() || document.targets.size > 8)
                return Refinement.Rejected(IntellijTargetFailure.INVALID_REGISTRY)
            val targets = ArrayList<DeclaredIntellijTarget>()
            for (target in document.targets) {
                if (targets.any { it.releaseLine.value == target.intellijReleaseLine })
                    return Refinement.Rejected(IntellijTargetFailure.DUPLICATE_TARGET)
                val ide =
                    when (val parsed = IdeBuildIdentity.parse(target.ideBuild)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected -> return Refinement.Rejected(IntellijTargetFailure.INVALID_REGISTRY)
                    }
                val kotlin =
                    when (val parsed = KotlinPluginBuildIdentity.parse(target.kotlinPluginBuild)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected -> return Refinement.Rejected(IntellijTargetFailure.INVALID_REGISTRY)
                    }
                if (target.intellijReleaseLine != ide.releaseLine.value || kotlin.releaseLine != ide.releaseLine)
                    return Refinement.Rejected(IntellijTargetFailure.TARGET_IDENTITY_MISMATCH)
                targets.add(DeclaredIntellijTarget(ide.releaseLine, ide, kotlin))
            }
            val default =
                targets.singleOrNull { it.releaseLine.value == document.defaultTarget }
                    ?: return Refinement.Rejected(IntellijTargetFailure.DEFAULT_TARGET_UNAVAILABLE)
            return Refinement.Refined(DeclaredIntellijTargets(default, targets))
        }
    }
}

@Serializable
private data class IntellijTargetsDocument(
    val schemaVersion: Int,
    val defaultTarget: String,
    val targets: List<IntellijTargetDocument>,
)

@Serializable
private data class IntellijTargetDocument(
    val intellijReleaseLine: String,
    val ideBuild: String,
    val kotlinPluginBuild: String,
)
