package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class DeclaredIntellijTargetsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `packaged profiles admit both known families and reject unknown future lines`() {
        val targets =
            assertInstanceOf<Refinement.Refined<DeclaredIntellijTargets>>(DeclaredIntellijTargets.packaged).value
        assertEquals("262", targets.defaultTarget.releaseLine.value)
        assertEquals(listOf("262", "263"), targets.targets.map { it.releaseLine.value })
        val selected =
            assertInstanceOf<Refinement.Refined<DeclaredIntellijTarget>>(targets.forVendorBuild("263.99999.1")).value
        assertEquals("263", selected.releaseLine.value)
        assertEquals(
            Refinement.Rejected(IntellijTargetFailure.TARGET_UNAVAILABLE),
            targets.forVendorBuild("264.1.2"),
        )
        assertEquals(
            Refinement.Rejected(IntellijTargetFailure.INVALID_IDE_BUILD),
            targets.forVendorBuild("263../other"),
        )
    }

    @Test
    fun `a declared profile cannot combine IDE and Kotlin families`() {
        reject(
            Registry(targets = listOf(Target("263", "263.1.2", "262.1.2-IJ"))),
            IntellijTargetFailure.TARGET_IDENTITY_MISMATCH,
        )
        reject(
            Registry(targets = listOf(Target("262", "263.1.2", "263.1.2-IJ"))),
            IntellijTargetFailure.TARGET_IDENTITY_MISMATCH,
        )
    }

    @Test
    fun `duplicate defaults unknown schema and malformed identities reject`() {
        reject(Registry(targets = listOf(Target(), Target())), IntellijTargetFailure.DUPLICATE_TARGET)
        reject(Registry(defaultTarget = "263"), IntellijTargetFailure.DEFAULT_TARGET_UNAVAILABLE)
        reject(Registry(schemaVersion = 2), IntellijTargetFailure.UNSUPPORTED_SCHEMA)
        reject(Registry(targets = listOf(Target(ideBuild = "262.invalid"))), IntellijTargetFailure.INVALID_REGISTRY)
        reject(Registry(targets = emptyList()), IntellijTargetFailure.INVALID_REGISTRY)
        assertEquals(
            Refinement.Rejected(IntellijTargetFailure.INVALID_REGISTRY),
            DeclaredIntellijTargets.decode(json.encodeToString(ExtraFieldRegistry())),
        )
    }

    private fun reject(value: Registry, failure: IntellijTargetFailure) {
        assertEquals(Refinement.Rejected(failure), DeclaredIntellijTargets.decode(json.encodeToString(value)))
    }
}

@Serializable
private data class Registry(
    val schemaVersion: Int = 1,
    val defaultTarget: String = "262",
    val targets: List<Target> = listOf(Target()),
)

@Serializable
private data class Target(
    val intellijReleaseLine: String = "262",
    val ideBuild: String = "262.1.2",
    val kotlinPluginBuild: String = "262.1.2-IJ",
)

@Serializable
private data class ExtraFieldRegistry(
    val schemaVersion: Int = 1,
    val defaultTarget: String = "262",
    val targets: List<Target> = listOf(Target()),
    val overrideFutureLines: Boolean = true,
)
