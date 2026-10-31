package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagementReceiptPreservationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `native publication preserves the admitted pin identity and guidance`() {
        val fixture = createLifecycleFixture(temporary)
        val prior = recordFacts(fixture.root, fixture.home)
        commitPublicExecutable(fixture.root, mapOf("HOME" to fixture.home.toString()), ReleaseChannel.STABLE)
        assertFacts(prior, (readReceipt(fixture.root) as ReceiptRead.Read).receipt)
    }

    @Test
    fun `registration changes preserve the admitted pin identity and guidance`() {
        val (root, home) = integrationFixture(temporary.toRealPath(), HarnessConnection.PI)
        val prior = recordFacts(root, home)
        connectHarness(root, home, HarnessConnection.PI)
        assertFacts(prior, (readReceipt(root) as ReceiptRead.Read).receipt)
        disconnectHarness(root, home, Harness.PI)
        val after = (readReceipt(root) as ReceiptRead.Read).receipt
        assertFacts(prior, after)
        assertEquals(prior.registrations, after.registrations)
    }

    private fun recordFacts(root: Path, home: Path): ManagementReceipt {
        val identity = InstalledBuildIdentity("v1.2.3", "1.2.3", "a".repeat(40), "b".repeat(40))
        val receipt =
            (readReceipt(root) as ReceiptRead.Read)
                .receipt
                .copy(
                    updatePolicy = UpdatePolicy.PinnedBuild(identity),
                    installedBuild = identity,
                    guidance =
                        listOf(
                            ManagedGuidance(
                                Harness.PI,
                                home.resolve(".pi/agent/skills/kast").toString(),
                                listOf(GuidanceFile("SKILL.md", "c".repeat(64))),
                            )
                        ),
                )
        writeManagementReceipt(root, receipt)
        return receipt
    }

    private fun assertFacts(prior: ManagementReceipt, after: ManagementReceipt) {
        assertEquals(prior.installedBuild, after.installedBuild)
        assertEquals(prior.updatePolicy, after.updatePolicy)
        assertEquals(prior.guidance, after.guidance)
    }
}
