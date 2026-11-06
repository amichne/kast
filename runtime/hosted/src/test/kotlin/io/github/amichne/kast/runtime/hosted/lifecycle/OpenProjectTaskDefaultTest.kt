package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.impl.OpenProjectTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real SDK DTOs characterize defaults without project effects or application startup. */
class OpenProjectTaskDefaultTest {
    @Test
    fun `direct project tasks keep configurators disabled for new and existing projects`() {
        val getter = OpenProjectTask::class.java.getMethod("getRunConfigurators")
        assertTrue(getter.returnType in setOf(Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType))
        for (newProject in listOf(false, true)) {
            val task = OpenProjectTask { isNewProject = newProject }
            assertNotEquals(true, getter.invoke(task), "Direct ProjectManager opening must not request configurators")
        }
        println(
            "SDK configurator getter: ${getter.returnType.name}, ${OpenProjectTask::class.java.protectionDomain.codeSource.location}"
        )
    }

    @Test
    fun `an enabled SDK task remains distinguishable from its disabled default`() {
        val getter = OpenProjectTask::class.java.getMethod("getRunConfigurators")
        val enabled = OpenProjectTask {
            javaClass.getMethod("setRunConfigurators", getter.returnType).invoke(this, true)
        }
        assertEquals(true, getter.invoke(enabled))
    }
}
