package io.github.amichne.kast.ide

import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.intellij.HostedChangeAdmission
import io.github.amichne.kast.change.intellij.HostedChangePorts
import io.github.amichne.kast.diagnostic.intellij.HostedDiagnosticAdmission
import io.github.amichne.kast.diagnostic.intellij.HostedDiagnosticPorts
import io.github.amichne.kast.relation.intellij.HostedRelationAdmission
import io.github.amichne.kast.relation.intellij.HostedRelationPorts
import io.github.amichne.kast.topology.intellij.HostedTopologyAdmission
import io.github.amichne.kast.topology.intellij.HostedTopologyPorts
import java.lang.reflect.Modifier
import kotlin.jvm.functions.Function1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class HostedProjectEffectAdmissionTest {
    @Test
    fun `hosted admissions expose only admitted or rejected states`() {
        listOf(
            HostedTopologyAdmission::class.java,
            HostedChangeAdmission::class.java,
            HostedRelationAdmission::class.java,
            HostedDiagnosticAdmission::class.java,
        ).forEach { admission ->
            assertEquals(
                setOf("Admitted", "Rejected"),
                admission.permittedSubclasses.mapTo(linkedSetOf()) { it.simpleName },
            )
        }
    }

    @Test
    fun `hosted ports cannot return Project or generic project callbacks`() {
        listOf(
            HostedTopologyPorts::class.java,
            HostedChangePorts::class.java,
            HostedRelationPorts::class.java,
            HostedDiagnosticPorts::class.java,
        ).forEach { ports ->
            ports.declaredFields.forEach { field ->
                assertFalse(Project::class.java.isAssignableFrom(field.type), ports.name)
                assertFalse(Function1::class.java.isAssignableFrom(field.type), ports.name)
            }
            ports.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.forEach { method ->
                assertFalse(Project::class.java.isAssignableFrom(method.returnType), method.name)
                assertFalse(
                    method.parameterTypes.any(Project::class.java::isAssignableFrom),
                    method.name,
                )
            }
        }
    }

    @Test
    fun `hosted factory bytecode has no ambient project discovery`() {
        listOf(
            "io/github/amichne/kast/topology/intellij/HostedIntellijTopologyPortsKt.class",
            "io/github/amichne/kast/topology/intellij/HostedWorkspaceSourceStateKt.class",
            "io/github/amichne/kast/change/intellij/HostedIntellijChangePortsKt.class",
            "io/github/amichne/kast/relation/intellij/HostedIntellijRelationPortsKt.class",
            "io/github/amichne/kast/diagnostic/intellij/HostedIntellijDiagnosticPortsKt.class",
        ).forEach { resource ->
            val bytes = HostedProjectEffectAdmissionTest::class.java.classLoader
                .getResourceAsStream(resource)
                ?.use { it.readAllBytes() }
            assertNotNull(bytes, resource)
            val constantPool = checkNotNull(bytes).toString(Charsets.ISO_8859_1)
            listOf(
                "ProjectManager",
                "openProjects",
                "withProject",
                "serviceLocator",
            ).forEach { forbidden -> assertFalse(forbidden in constantPool, "$resource: $forbidden") }
        }
    }
}
