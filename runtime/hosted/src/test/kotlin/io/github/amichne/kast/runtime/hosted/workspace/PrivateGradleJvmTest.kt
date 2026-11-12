package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrivateGradleJvmTest {
    @Test
    fun `incompatible bundled runtime selects a validated local installation`() {
        val selected =
            PrivateGradleJvm.select(
                PrivateGradleJvmDeclaration.Unspecified,
                "/jbr25",
                linkedMapOf(
                    "/jbr25" to GradleJavaHomeCompatibility.UNSUPPORTED_JAVA,
                    "/broken" to GradleJavaHomeCompatibility.INVALID_HOME,
                    "/jdk21" to GradleJavaHomeCompatibility.COMPATIBLE,
                ),
            ) as Refinement.Refined
        assertEquals("/jdk21", selected.value.home)
        assertEquals(PrivateGradleJvmAuthority.LOCAL_INSTALLATION, selected.value.authority)
    }

    @Test
    fun `compatible bundled runtime needs no discovery`() {
        val primary =
            privateGradleJvmHomes(PrivateGradleJvmDeclaration.Unspecified, "/jbr") {
                    error("Discovery is not needed")
                }
                .first()
        val selected =
            PrivateGradleJvm.select(
                PrivateGradleJvmDeclaration.Unspecified,
                "/jbr",
                mapOf(primary to GradleJavaHomeCompatibility.COMPATIBLE),
            ) as Refinement.Refined
        assertEquals("/jbr", selected.value.home)
        assertEquals(PrivateGradleJvmAuthority.BUNDLED_RUNTIME, selected.value.authority)
    }

    @Test
    fun `discovery excludes previously inspected homes and retains search order`() {
        assertEquals(
            listOf("/jbr25", "/broken", "/jdk21"),
            privateGradleJvmHomes(PrivateGradleJvmDeclaration.Unspecified, "/jbr25") {
                    listOf("/jbr25", "/broken", "/broken", "/jdk21")
                }
                .toList(),
        )
    }

    @Test
    fun `declared homes are authoritative and never fall back`() {
        for (declaration in
            listOf(
                PrivateGradleJvmDeclaration.GradleProperty("/declared"),
                PrivateGradleJvmDeclaration.LocalProperty("/declared"),
            )) {
            assertEquals(
                listOf("/declared"),
                privateGradleJvmHomes(declaration, "/jbr") { error("Must not override a declaration") }.toList(),
            )
            for ((observation, failure) in
                listOf(
                    GradleJavaHomeCompatibility.INVALID_HOME to PrivateGradleJvmFailure.DECLARED_HOME_INVALID,
                    GradleJavaHomeCompatibility.UNSUPPORTED_JAVA to PrivateGradleJvmFailure.DECLARED_JAVA_UNSUPPORTED,
                )) {
                assertEquals(
                    Refinement.Rejected(failure),
                    PrivateGradleJvm.select(
                        declaration,
                        "/jbr",
                        mapOf("/declared" to observation, "/jdk21" to GradleJavaHomeCompatibility.COMPATIBLE),
                    ),
                )
            }
        }
    }

    @Test
    fun `valid declarations preserve their source and home`() {
        for ((declaration, authority) in
            listOf(
                PrivateGradleJvmDeclaration.GradleProperty("/declared") to PrivateGradleJvmAuthority.GRADLE_PROPERTY,
                PrivateGradleJvmDeclaration.LocalProperty("/declared") to PrivateGradleJvmAuthority.LOCAL_PROPERTY,
            )) {
            val selected =
                PrivateGradleJvm.select(
                    declaration,
                    "/jbr",
                    mapOf("/declared" to GradleJavaHomeCompatibility.COMPATIBLE),
                ) as Refinement.Refined
            assertEquals("/declared", selected.value.home)
            assertEquals(authority, selected.value.authority)
        }
    }

    @Test
    fun `missing observations cannot establish a compatible installation`() {
        for (declaration in
            listOf(
                PrivateGradleJvmDeclaration.Unspecified,
                PrivateGradleJvmDeclaration.GradleProperty("/declared"),
                PrivateGradleJvmDeclaration.LocalProperty("/declared"),
            )) {
            assertEquals(
                Refinement.Rejected(PrivateGradleJvmFailure.HOME_NOT_INSPECTED),
                PrivateGradleJvm.select(declaration, "/jbr25", emptyMap()),
            )
        }
    }

    @Test
    fun `no compatible local installation rejects without manufacturing success`() {
        assertEquals(
            Refinement.Rejected(PrivateGradleJvmFailure.NO_COMPATIBLE_LOCAL_JDK),
            PrivateGradleJvm.select(
                PrivateGradleJvmDeclaration.Unspecified,
                "/jbr25",
                mapOf(
                    "/jbr25" to GradleJavaHomeCompatibility.UNSUPPORTED_JAVA,
                    "/jdk25" to GradleJavaHomeCompatibility.UNSUPPORTED_JAVA,
                ),
            ),
        )
    }
}
