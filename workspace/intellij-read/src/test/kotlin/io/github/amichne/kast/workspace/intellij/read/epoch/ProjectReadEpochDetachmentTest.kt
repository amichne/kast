package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.project.Project
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Actual-object-graph proof for epochs emitted by the production observation path. */
class ProjectReadEpochDetachmentTest {
    @Test
    fun `production epoch object graph retains no callback or live authority`() {
        val observed = LiveProjectReadEpochSource(
            RecordingProjectReadEpochPlatform(),
            ProjectReadEpochMetadataCounter(),
            ProjectReadEpochMetadataCounter(),
            RecordingProjectReadEpochExecution(),
        ).source.observe()
        val epoch = assertInstanceOf(ProjectReadEpochObservation.Observed::class.java, observed).epoch

        assertDetached(epoch, IdentityHashMap())
    }

    private fun assertDetached(value: Any, visited: IdentityHashMap<Any, Boolean>) {
        if (visited.put(value, true) != null) return
        val type = value.javaClass
        assertFalse(value is Function<*>, type.name)
        assertFalse(value is Project, type.name)
        assertFalse(value is ProjectReadEpoch.Source<*>, type.name)
        assertFalse(value is ProjectReadEpochMetadataCounter, type.name)
        if (!type.name.startsWith("io.github.amichne.kast.workspace.")) return
        type.declaredFields
            .filterNot { field -> Modifier.isStatic(field.modifiers) }
            .forEach { field ->
                field.isAccessible = true
                field.get(value)?.let { nested -> assertDetached(nested, visited) }
            }
    }
}
