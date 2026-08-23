package io.github.amichne.kast.workspace.intellij

import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class InstalledGradleImportPlanTest {
    @Test
    fun `linked exact workspace awaits project-open sync without starting another refresh`() {
        val linkedSettings = GradleProjectSettings("/workspace")

        val operation = InstalledGradleLinkPresence.Linked(linkedSettings).importOperation()

        val await = assertInstanceOf(
            InstalledGradleImportOperation.AwaitLinked::class.java,
            operation,
        )
        assertSame(linkedSettings, await.settings)
    }

    @Test
    fun `unlinked exact workspace is linked`() {
        assertInstanceOf(
            InstalledGradleImportOperation.LinkUnlinked::class.java,
            InstalledGradleLinkPresence.Unlinked.importOperation(),
        )
    }
}
