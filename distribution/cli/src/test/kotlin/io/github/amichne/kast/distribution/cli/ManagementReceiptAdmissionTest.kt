package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ManagementReceiptAdmissionTest {
    private val root = Path.of("/home/example/.local/share/kast")
    private val stable = InstalledBuildIdentity("v1.2.3", "1.2.3", "a".repeat(40), "b".repeat(40))
    private val main =
        InstalledBuildIdentity("developer-v0.0.1", "0.0.1", "a".repeat(40), "b".repeat(40), "c".repeat(40))
    private val receipt =
        ManagementReceipt(
            2,
            root.toString(),
            "/home/example/.local/bin/kast",
            "d".repeat(64),
            ReleaseChannel.STABLE,
            emptyList(),
        )
    private val guidance =
        ManagedGuidance(
            Harness.PI,
            "/home/example/.pi/agent/skills/kast",
            listOf(GuidanceFile("SKILL.md", "e".repeat(64))),
        )

    @Test
    fun `legacy tracked receipts and consistent installed identities remain admitted`() {
        val legal =
            listOf(
                receipt,
                receipt.copy(updatePolicy = UpdatePolicy.TrackMain),
                receipt.copy(installedBuild = stable),
                receipt.copy(installedBuild = main, updatePolicy = UpdatePolicy.TrackMain),
                receipt.copy(
                    installedBuild = stable,
                    updatePolicy = UpdatePolicy.PinnedBuild(stable),
                    guidance = listOf(guidance),
                ),
            )
        for (value in legal) assertEquals(ReceiptRead.Read(value), admit(value))
    }

    @Test
    fun `policy admission rejects mismatched pins and channel identities`() {
        val illegal =
            listOf(
                receipt.copy(updatePolicy = UpdatePolicy.PinnedBuild(stable)),
                receipt.copy(installedBuild = main),
                receipt.copy(installedBuild = stable, updatePolicy = UpdatePolicy.TrackMain),
                receipt.copy(installedBuild = main, updatePolicy = UpdatePolicy.PinnedBuild(stable)),
            )
        for (value in illegal) assertInstanceOf(ReceiptRead.Unavailable::class.java, admit(value))
    }

    @Test
    fun `guidance admission rejects duplicate harnesses and duplicate files`() {
        val illegal = listOf(listOf(guidance, guidance), listOf(guidance.copy(files = guidance.files + guidance.files)))
        for (values in illegal) assertInstanceOf(
            ReceiptRead.Unavailable::class.java,
            admit(receipt.copy(guidance = values)),
        )
    }

    @Test
    fun `guidance admission rejects unsafe paths and malformed digests`() {
        val paths =
            listOf(
                "",
                "/SKILL.md",
                "../SKILL.md",
                "resources/../SKILL.md",
                "resources//ref.md",
                "a\\ref.md",
                "./SKILL.md",
            )
        for (path in paths) assertInvalidGuidance(guidance.copy(files = listOf(GuidanceFile(path, "e".repeat(64)))))
        assertInvalidGuidance(guidance.copy(files = listOf(GuidanceFile("SKILL.md", "invalid"))))
        assertInvalidGuidance(guidance.copy(destination = "relative/kast"))
        assertInvalidGuidance(guidance.copy(destination = "/home/example/../kast"))
        assertInvalidGuidance(guidance.copy(files = emptyList()))
    }

    private fun assertInvalidGuidance(value: ManagedGuidance) {
        assertEquals(ReceiptRead.Unavailable("receipt_invalid"), admit(receipt.copy(guidance = listOf(value))))
    }

    private fun admit(value: ManagementReceipt): ReceiptRead =
        admitManagementReceipt(root, managementJson.encodeToString(value))
}
