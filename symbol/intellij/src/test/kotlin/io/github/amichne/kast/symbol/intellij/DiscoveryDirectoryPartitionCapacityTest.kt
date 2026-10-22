package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.intellij.DirectoryDiscoveryPartitionTestFixture.Case
import io.github.amichne.kast.symbol.intellij.DirectoryDiscoveryPartitionTestFixture.discoverPartitions
import io.github.amichne.kast.symbol.intellij.DirectoryDiscoveryPartitionTestFixture.refined
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiscoveryDirectoryPartitionCapacityTest {
    @Test
    fun `bounded work files retention and missing partitions keep finite incomplete evidence`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home.resolve("repo"))
            val base = case.request(SymbolDiscoveryContainment.DESCENDANTS, "Target")
            for ((work, environment, expected) in
                listOf(
                    Triple(1L, emptyMap(), SymbolDiscoveryQualification.WORK_LIMIT_REACHED),
                    Triple(
                        10_000L,
                        mapOf("KAST_READ_DISCOVERY_FILES" to "1"),
                        SymbolDiscoveryQualification.WORK_LIMIT_REACHED,
                    ),
                    Triple(
                        10_000L,
                        mapOf("KAST_READ_QUERY_CHECKPOINT_BYTES" to "1024"),
                        SymbolDiscoveryQualification.BYTE_LIMIT_REACHED,
                    ),
                )) {
                val request =
                    SymbolDiscoveryRequest(
                        scope = base.scope,
                        target = base.target as SymbolDiscoveryTarget.Name,
                        budget =
                            base.budget.copy(
                                resources =
                                    org.junit.jupiter.api.Assertions.assertInstanceOf(
                                            io.github.amichne.kast.kernel.ResourceBudget.Bounded::class.java,
                                            base.budget.resources,
                                        )
                                        .copy(workUnitLimit = WorkUnitLimit.parse(work).refined())
                            ),
                        constraints = base.constraints,
                    )
                val observation = RecordingDiscoveryObservation { 0L }
                val query =
                    IntellijNativeDiscoveryQuery(
                        environmentState = { IntellijDiscoveryEnvironmentState.READY },
                        cancellationCheck = {},
                        clock = IntellijReadNanoClock { 0L },
                        observation = observation,
                        limits = ReadLimits.resolve(environment = environment).refined(),
                    )
                val result = query.discoverPartitions(project, case.scope(request, observation), request).outcome()
                assertTrue(result is SymbolDiscoveryOutcome.Qualified)
                assertTrue(expected in (result as SymbolDiscoveryOutcome.Qualified).qualifications.values)
                assertTrue(result.batch().examinedWorkUnits.value <= work)
            }
        }
}
