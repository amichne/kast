package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostedReadProjectClasspathTest {
    @Test
    fun `exact project artifacts admit immutable all-zero classpath proof`() {
        val classpath = fixtureProjectClasspath(hostedModule())

        assertEquals(canonicalProjects, classpath.projectPaths().toSet())
        assertTrue(classpath.classCount >= 3)
        assertTrue(classpath.prohibitedEffectCounts().all { it.count == 0 })
        classpath.artifacts().toMutableList().clear()
        assertEquals(3, classpath.artifacts().size)
    }

    @Test
    fun `missing unknown duplicate malformed and forbidden project artifacts reject`() {
        val module = hostedModule()
        val canonical = canonicalProjectJars()
        assertRejected(canonical.dropLast(1), module)
        assertRejected(
            canonical + HostedReadProjectJarBytes.capture(
                ":unknown",
                "unknown.jar",
                fixtureJar("fixture/unknown/Unknown"),
            ),
            module,
        )
        assertRejected(canonical + canonical.first(), module)
        assertRejected(
            canonical.map { jar ->
                if (jar.projectPath == ModuleId.KERNEL.projectPath) {
                    HostedReadProjectJarBytes.capture(jar.projectPath, jar.artifactName, byteArrayOf())
                } else jar
            },
            module,
        )
        assertRejected(
            canonical.map { jar ->
                if (jar.projectPath == ModuleId.KERNEL.projectPath) {
                    HostedReadProjectJarBytes.capture(
                        jar.projectPath,
                        jar.artifactName,
                        fixtureJarWithCall(
                            "io/github/amichne/kast/kernel/ForbiddenWriter",
                            JvmMember.of("java/nio/file/Files", "deleteIfExists", "()V"),
                        ),
                    )
                } else jar
            },
            module,
        )
        listOf(
            JvmMember.of("java/nio/file/Files", "walk", "()V"),
            JvmMember.of("java/nio/file/Files", "readAllBytes", "()V"),
            JvmMember.of("java/security/MessageDigest", "update", "()V"),
            JvmMember.of("java/net/http/HttpClient", "send", "()V"),
            JvmMember.of("java/lang/Thread", "sleep", "()V"),
        ).forEachIndexed { index, target ->
            assertRejected(
                canonical.map { jar ->
                    if (jar.projectPath == ModuleId.KERNEL.projectPath) {
                        HostedReadProjectJarBytes.capture(
                            jar.projectPath,
                            jar.artifactName,
                            fixtureJarWithCall("fixture/project/Forbidden$index", target),
                        )
                    } else jar
                },
                module,
            )
        }
    }

    @Test
    fun `cross-role project proof cannot satisfy hosted scan policy`() {
        val architecture =
            (KastArchitecturePolicy.validate() as ArchitecturePolicyValidation.Valid).architecture
        val hosted = architecture.modules.getValue(ModuleId.WORKSPACE_INTELLIJ_READ)
        val legacy = architecture.modules.getValue(ModuleId.WORKSPACE_INTELLIJ)
        val legacyProject = fixtureProjectClasspath(legacy)

        assertInstanceOf<HostedReadClasspathPolicyRefinement.Rejected>(
            HostedReadClasspathScanPolicy.refine(
                hosted,
                legacyProject.scanPolicy,
                HostedReadClasspathScanPolicy.capture(hosted),
            ),
        )
    }

    private fun assertRejected(
        jars: List<HostedReadProjectJarBytes>,
        module: ValidatedModulePolicy,
    ) {
        assertInstanceOf<HostedReadProjectClasspathRefinement.Rejected>(
            HostedReadProjectClasspath.refine(module, jars, canonicalProjects),
        )
    }

    private fun canonicalProjectJars(): List<HostedReadProjectJarBytes> = listOf(
        ModuleId.KERNEL,
        ModuleId.PROTOCOL_CONTRACT,
        ModuleId.WORKSPACE_CONTRACT,
    ).mapIndexed { index, project ->
        HostedReadProjectJarBytes.capture(
            project.projectPath,
            "project-$index.jar",
            fixtureJar("fixture/project/Project$index"),
        )
    }

    private fun hostedModule(): ValidatedModulePolicy =
        (KastArchitecturePolicy.validate() as ArchitecturePolicyValidation.Valid)
            .architecture.modules.getValue(ModuleId.WORKSPACE_INTELLIJ_READ)

    private companion object {
        val canonicalProjects = setOf(
            ModuleId.KERNEL,
            ModuleId.PROTOCOL_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        )
    }
}
