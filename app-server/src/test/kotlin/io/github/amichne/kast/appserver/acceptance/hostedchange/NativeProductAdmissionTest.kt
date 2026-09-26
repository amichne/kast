package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class NativeProductAdmissionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `native read coverage excludes changes from complete installed catalog`() {
        val catalog = CanonicalAgentToolDefinitions.all
        assertEquals(8, catalog.size)
        assertEquals(6, nativeReadToolNames.size)
        assertEquals(
            emptySet<String>(),
            nativeReadToolNames.intersect(setOf("change_plan", "change_apply", "change_recover")),
        )
    }

    @Test
    fun `source-built command retains exact owned product layout`() {
        val root = temporary.toRealPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val product = Files.createDirectory(root.resolve("product"))
        assertEquals(product.resolve("bin/kast"), NativeProductAdmission.executable(product, workspace))
        assertThrows<NativeRejected> { NativeProductAdmission.executable(root, workspace) }
    }

    @Test
    fun `released command is exact wrapper with unchanged manifest and wrapper bytes`() {
        val root = temporary.toRealPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val payload = "a".repeat(64)
        val product = Files.createDirectories(root.resolve("installation/versions/1.2.3-$payload"))
        val executable = product.resolve("bin/kast-complete")
        Files.createDirectory(executable.parent)
        Files.writeString(executable, "#!/bin/sh\nexit 0\n")
        executable.toFile().setExecutable(true)
        val manifest = product.resolve("installation.json")
        Files.writeString(
            manifest,
            Json.encodeToString(
                WrapperManifest(
                    listOf(WrapperFile("bin/kast-complete", "sha256:${sha256(Files.readAllBytes(executable))}"))
                )
            ),
        )
        val witness =
            NativeReleasedProductWitness(
                schemaVersion = 1,
                ownedRoot = root.toString(),
                product = product.toString(),
                executable = executable.toString(),
                pluginsDirectory = root.resolve("home/plugins").toString(),
                version = "1.2.3",
                sourceCommit = "b".repeat(40),
                installerSha256 = payload,
                controlSha256 = payload,
                hostedPluginSha256 = payload,
                payloadIdentity = "sha256:$payload",
                installationManifestSha256 = sha256(Files.readAllBytes(manifest)),
            )
        Files.writeString(root.resolve("released-product-admission.json"), Json.encodeToString(witness))
        assertEquals(executable, NativeProductAdmission.executable(product, workspace))
        assertThrows<NativeRejected> { NativeProductAdmission.executable(root, workspace) }
        Files.writeString(executable, "#!/bin/sh\nexit 1\n")
        assertThrows<NativeRejected> { NativeProductAdmission.executable(product, workspace) }
        Files.writeString(manifest, "changed manifest")
        assertThrows<NativeRejected> { NativeProductAdmission.executable(product, workspace) }
    }
}

@Serializable private data class WrapperManifest(val payloadFiles: List<WrapperFile>)

@Serializable private data class WrapperFile(val path: String, val sha256: String)
