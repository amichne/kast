package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.DeclaredIntellijTargets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real Kotlin serialization and staged Python admission, without installation, release I/O or IDE effects. */
class NativeSelectionConsumerInteropTest {
    @TempDir lateinit var home: Path

    @Test
    fun `actual captured selections preserve both target records and finite legacy rejection in staged Python`() {
        val root = Path.of(checkNotNull(System.getProperty("kast.selectionInteropRoot")))
        val python = Path.of(checkNotNull(System.getProperty("kast.selectionInteropPython")))
        val staged = Files.createDirectories(home.resolve("staged/share/kast"))
        for (name in listOf("managed_release.py", "managed_update_journal.py")) Files.copy(
            root.resolve("packaging/$name"),
            staged.resolve(name),
        )
        Files.copy(root.resolve("config/intellij-targets.json"), staged.resolve("intellij-targets.json"))
        val script = home.resolve("verify-capture.py")
        checkNotNull(javaClass.getResourceAsStream("/selection/verify-capture.py")).use {
            Files.copy(it, script)
        }
        val report = Files.createDirectories(Path.of(checkNotNull(System.getProperty("kast.selectionInteropReport"))))
        val captures = Files.createDirectories(report.resolve("captures"))
        val targets =
            (DeclaredIntellijTargets.packaged as Refinement.Refined).value.targets.map { target ->
                val line = target.releaseLine.value
                MainHostTarget(
                    line,
                    target.ideBuild.value,
                    target.kotlinPluginBuild.value,
                    BuildRecordReference(
                        "kast-host-release-v0.0.5000" + (if (line == "262") "" else "-idea-$line") + ".json",
                        (if (line == "262") "1" else "2").repeat(64),
                    ),
                )
            }
        val versioned =
            VersionedMainBuildEntry(
                "a".repeat(40),
                "b".repeat(40),
                "c".repeat(40),
                "developer-v0.0.5000",
                "0.0.5000",
                listOf("macos-aarch64"),
                BuildRecordReference("candidate-v2.json", "3".repeat(64)),
                BuildRecordReference("promotion-v2.json", "4".repeat(64)),
                targets,
            )
        val main =
            resolveBuildSelection(BuildRequest.Main) {
                ReleaseDocumentRead.Read(managementJson.encodeToString(VersionedMainBuildCatalog(2, listOf(versioned))))
            }
        Files.writeString(
            captures.resolve("v2.json"),
            managementJson.encodeToString((main as SelectionAdmission.Admitted).value),
        )
        val legacy =
            LegacyMainBuildEntry(
                "d".repeat(40),
                "e".repeat(40),
                "f".repeat(40),
                "developer-v0.0.4999",
                "0.0.4999",
                listOf("macos-aarch64"),
                BuildRecordReference("candidate.json", "5".repeat(64)),
                BuildRecordReference("promotion.json", "6".repeat(64)),
            )
        val fallback =
            resolveBuildSelection(BuildRequest.Main) { url ->
                if (url.endsWith("builds-v2.json"))
                    ReleaseDocumentRead.Unavailable(
                        ReleaseReadObservation.Unavailable(ReleaseReadStage.RESPONSE, ReleaseReadFailure.NOT_FOUND)
                    )
                else ReleaseDocumentRead.Read(managementJson.encodeToString(MainBuildCatalog(1, listOf(legacy))))
            }
        Files.writeString(
            captures.resolve("v1.json"),
            managementJson.encodeToString((fallback as SelectionAdmission.Admitted).value),
        )
        val version = (StableVersion.parse("1.2.3") as SelectionAdmission.Admitted).value
        val stable =
            resolveBuildSelection(BuildRequest.Version(version)) { error("no discovery for explicit stable fixture") }
        Files.writeString(
            captures.resolve("stable.json"),
            managementJson.encodeToString((stable as SelectionAdmission.Admitted).value),
        )
        val output = report.resolve("python.log")
        val process =
            ProcessBuilder(
                    python.toString(),
                    "-I",
                    script.toString(),
                    "--staged",
                    staged.toString(),
                    "--captures",
                    captures.toString(),
                )
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "prepared Python selection body timed out")
            assertEquals(0, process.exitValue(), Files.readString(output))
            assertEquals("SELECTION_CONTRACT_METADATA_FIXTURE: 5 target checks passed", Files.readString(output).trim())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "owned Python child did not settle")
        }
    }
}
