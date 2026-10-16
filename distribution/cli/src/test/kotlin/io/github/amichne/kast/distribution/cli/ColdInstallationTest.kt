package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationShutdownRequest
import io.github.amichne.kast.distribution.contract.installationResetFence
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class ColdInstallationTest {
    @Test
    fun `completed installation lifts its exact shutdown fence without a child effect`(@TempDir temporary: Path) {
        val fixture = createLifecycleFixture(temporary)
        val fence = fixture.root.resolve(SHUTDOWN_FENCE)
        Files.writeString(
            fence,
            managementJson.encodeToString(InstallationShutdownRequest(fixture.installation.toString())),
        )
        val effects = mutableListOf<String>()
        val admitted =
            assertInstanceOf(
                LifecycleAdmission.Admitted::class.java,
                InstallationLifecycle.admit(
                    fixture.root,
                    fixture.home,
                    emptyMap(),
                    LifecycleExecution(
                        child =
                            LifecycleChildExecutor { command, _, _ ->
                                effects += command.last()
                                LifecycleChildObservation.Exited(0)
                            },
                        processes = closedHostProcesses(),
                        observe = {},
                    ),
                ),
            )

        assertEquals(LifecycleEffect.Completed, admitted.lifecycle.resume())
        assertEquals(emptyList<String>(), effects)
        assertFalse(Files.exists(fence))
    }

    @Test
    fun `force reinstall completes a qualified payload without activation or readiness effects`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath().resolve("kast")
        val home = Files.createDirectories(temporary.resolve("home"))
        val retirement = ResetRuntimeScript(root, closedRetirementSteps())
        val installer = ForceReinstaller { erased, _, _ ->
            prepareFreshPayload(erased.root)
            val report = temporary.resolve("report.json")
            writeStagedReport(report)
            FreshResetInstallation.admit(erased.root, report)
        }

        val outcome =
            forceResetInstallation(
                root,
                home,
                emptyMap(),
                ForceResetOperation.REINSTALL,
                ForceResetExecution(retirement, installer, {}),
            )

        assertEquals(
            "RESET_INSTALLED",
            managementJson.parseToJsonElement(outcome.asJson()).jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertFalse(managementJson.parseToJsonElement(outcome.asJson()).jsonObject.containsKey("generation"))
        assertFalse(Files.exists(installationResetFence(root)))
        retirement.assertConsumed()
    }
}
