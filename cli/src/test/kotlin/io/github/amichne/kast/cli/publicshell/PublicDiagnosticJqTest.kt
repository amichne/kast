package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicDiagnosticJqTest {
    // Budget: one physical executable marker; the jq exchange is controlled.
    // No child, IDE, source access, or lifecycle effect. Only routing and presentation are established.
    @Test
    fun `diagnostic identity survives jq preflight and successful projection`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val executable = Files.writeString(root.resolve("jq"), "fixture")
        check(executable.toFile().setExecutable(true))
        val stages = mutableListOf<PublicJqStage>()
        val executor = PublicJqExecutor { request ->
            stages.add(request.stage)
            Refinement.Refined(
                PublicJqCapture(
                    0,
                    when (request.stage) {
                        PublicJqStage.VERSION -> "jq-1.8.2\n"
                        PublicJqStage.COMPILE -> ""
                        PublicJqStage.FILTER -> Json.encodeToString(true)
                        PublicJqStage.EXECUTABLE,
                        PublicJqStage.PRESENTATION -> error("This stage cannot execute a child")
                    },
                )
            )
        }
        val session =
            KastDirectToolSession(
                emptyList(),
                { error("No root discovery") },
                { error("No preparation") },
                { error("No native invocation") },
            )
        val output =
            preparePublicCli(
                listOf("diagnostic", "check", "--path", ".", "--jq", ".", "--jq-executable", executable.toString()),
                ByteArrayInputStream(byteArrayOf()),
                root,
                session = { _, _ -> session },
                invoke = { _, admitted ->
                    assertEquals(PublicToolIdentity.CHECK_DIAGNOSTICS, admitted.identity)
                    assertTrue(admitted.canonical is PublicToolCanonical.Diagnostics)
                    assertEquals(listOf(PublicJqStage.VERSION, PublicJqStage.COMPILE), stages)
                    PublicPreparedOutput(Json.encodeToString(emptyList<String>()), PublicShellExit.COMPLETE)
                },
                jqExecutor = executor,
            )
        assertEquals(PublicShellExit.COMPLETE, output.exit)
        assertEquals("true", output.text)
        assertEquals(listOf(PublicJqStage.VERSION, PublicJqStage.COMPILE, PublicJqStage.FILTER), stages)
    }
}
