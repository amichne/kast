package support.architecture

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A private Gradle build exercises the real collector and command without source compilation or resolution. */
class TopologyImpactTaskTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `command reports production consumers and excludes test dependencies`() {
        fixture()
        val result = runner("-PkastTopologyModules=:symbol:contract").build()
        val report = report()
        assertEquals("COMPUTED", report.getValue("status").jsonPrimitive.content)
        assertEquals("1", report.getValue("schemaVersion").jsonPrimitive.content)
        // SHA-256 of the independently enumerated, compact graph document for this fixture.
        assertEquals(
            "sha256:3bf14f88af52403a653d4b49313a41eb452375ecc32ace0bebd1f48eddcca590",
            report.getValue("graphIdentity").jsonPrimitive.content,
        )
        assertEquals(
            setOf(
                "status", "schemaVersion", "graphIdentity", "graph", "selectedModules", "affectedModules", "evidence",
            ),
            report.keys,
        )
        assertEquals(
            "DECLARED_PRODUCTION_PROJECT_DEPENDENCIES",
            report.getValue("evidence").jsonPrimitive.content,
        )
        val affected = report.getValue("affectedModules").jsonArray
        assertEquals(listOf(":query:contract"), affected.map { it.jsonObject.getValue("module").jsonPrimitive.content })
        assertEquals(
            listOf(":query:contract", ":symbol:contract"),
            affected.single().jsonObject.getValue("dependencyPath").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(result.tasks.any { it.path.endsWith("compileJava") || it.path == ":verifyKastArchitecture" })
        assertTrue(result.output.contains("topology-impact.json"), result.output)
        assertEquals(2, report.getValue("graph").jsonObject.getValue("dependencies").jsonArray.size)
        val repeated = runner("-PkastTopologyModules=:symbol:contract").build()
        assertTrue(repeated.output.contains("Reusing configuration cache"), repeated.output)
        assertEquals(report, report())
    }

    @Test
    fun `unknown selection replaces previous success with typed rejection`() {
        fixture()
        runner("-PkastTopologyModules=:symbol:contract").build()
        val result = runner("-PkastTopologyModules=:unknown").buildAndFail()
        assertTrue(result.output.contains("UNKNOWN_MODULE"), result.output)
        assertEquals("REJECTED", report().getValue("status").jsonPrimitive.content)
        assertEquals("UNKNOWN_MODULE", report().getValue("code").jsonPrimitive.content)
        assertFalse("affectedModules" in report())
    }

    @Test
    fun `disable switch replaces previous success without interpreting selection`() {
        fixture()
        runner("-PkastTopologyModules=:symbol:contract").build()
        runner("-PkastTopologyEnabled=false", "-PkastTopologyModules=:unknown").build()
        assertEquals(setOf("status", "schemaVersion"), report().keys)
        assertEquals("DISABLED", report().getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `invalid disable switch fails closed`() {
        fixture()
        runner("-PkastTopologyEnabled=FALSE", "-PkastTopologyModules=:symbol:contract").buildAndFail()
        assertEquals("INVALID_SWITCH", report().getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `ordinary help does not schedule topology report`() {
        fixture()
        val result = runner().withArguments("help", "--dry-run", "--console=plain").build()
        assertFalse(result.tasks.any { it.path == ":topologyImpact" })
        assertFalse(java.nio.file.Files.exists(directory.resolve("build/reports/topology/topology-impact.json")))
    }

    private fun fixture() {
        directory.resolve("settings.gradle").writeText(
            "rootProject.name = 'topology-impact-fixture'\n" +
                "include ':kernel', ':symbol:contract', ':query:contract', ':source:contract'\n",
        )
        directory.resolve("build.gradle").writeText(
            """
            plugins { id 'kast.architecture' }
            [':kernel', ':symbol:contract', ':query:contract', ':source:contract'].each {
                project(it) { apply plugin: 'java-library' }
            }
            project(':symbol:contract') { dependencies { api project(':kernel') } }
            project(':query:contract') { dependencies { implementation project(':symbol:contract') } }
            project(':source:contract') { dependencies { testImplementation project(':symbol:contract') } }
            """.trimIndent(),
        )
        listOf("kernel", "symbol/contract", "query/contract", "source/contract").forEach {
            directory.resolve(it).createDirectories()
        }
    }

    private fun runner(vararg properties: String): GradleRunner = GradleRunner.create()
        .withProjectDir(directory.toFile())
        .withPluginClasspath()
        .withArguments("topologyImpact", "--configuration-cache", "--console=plain", "--no-watch-fs", *properties)

    private fun report() = Json.parseToJsonElement(
        directory.resolve("build/reports/topology/topology-impact.json").readText(),
    ).jsonObject
}
