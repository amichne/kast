package io.github.amichne.kast.api.contract.transformation.admission.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

abstract class RepositoryOperationAdmissionFixture {
    @TempDir
    protected lateinit var temporaryDirectory: Path

    protected fun admitted(input: RawRepositoryOperationInput): AdmittedRepositoryOperation =
        assertInstanceOf(
            RepositoryOperationAdmission.Result.Admitted::class.java,
            RepositoryOperationAdmission.admit(input),
        ).operation

    protected fun assertRejected(
        expected: RepositoryOperationRejection,
        input: RawRepositoryOperationInput,
    ) = assertRejectedResult(expected, RepositoryOperationAdmission.admit(input))

    protected fun assertRejectedResult(
        expected: RepositoryOperationRejection,
        result: RepositoryOperationAdmission.Result,
    ) {
        val rejected = assertInstanceOf(
            RepositoryOperationAdmission.Result.Rejected::class.java,
            result,
        )
        assertEquals(expected, rejected.rejection)
        assertFalse(rejected.rejection.mutationStarted)
    }

    protected fun validInput(
        root: Path = repositoryRoot(),
        sourceState: RawSourceStateInput = validSourceState(root = root),
        buildOwnership: RawBuildOwnershipEvidence = available(validCompilationUnit()),
        scope: List<RawScopeSelector> = listOf(RawScopeSelector.Module("application")),
        resourceBounds: RawResourceBoundsInput = validBounds(),
    ): RawRepositoryOperationInput = RawRepositoryOperationInput(
        repository = RawRepositoryInput(
            requestedRoot = root.toString(),
            baseDirectory = root.parent.toString(),
        ),
        sourceState = sourceState,
        buildOwnership = buildOwnership,
        scope = scope,
        resourceBounds = resourceBounds,
    )

    protected fun repositoryRoot(name: String = "repository"): Path =
        temporaryDirectory.resolve(name).also { root ->
            if (!Files.exists(root.resolve(".git"))) {
                Files.createDirectories(root.resolve("misleading-layout/src"))
                Files.writeString(
                    root.resolve("misleading-layout/src/Application.kt"),
                    "package sample\nclass Application\n",
                )
                git(root, "init", "--quiet")
                git(root, "config", "user.name", "Kast Admission Test")
                git(root, "config", "user.email", "kast-admission@example.invalid")
                git(root, "add", ".")
                git(root, "commit", "--quiet", "-m", "fixture")
            }
        }

    protected fun validSourceState(
        inputs: List<RawSourceInput> = emptyList(),
        root: Path = repositoryRoot(),
    ): RawSourceStateInput = RawSourceStateInput(
        revision = git(root, "rev-parse", "HEAD"),
        inputs = inputs,
    )

    protected fun git(
        root: Path,
        vararg arguments: String,
    ): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }.trim()
        check(process.waitFor() == 0) {
            "git ${arguments.joinToString(" ")} failed in $root: $output"
        }
        return output
    }

    protected fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    protected fun includedSource(
        path: String,
        kind: RawSourceInputKind,
        digest: String = DIGEST_A,
    ): RawSourceInput = RawSourceInput(
        path = path,
        kind = kind,
        presence = RawSourceInputPresence.PRESENT,
        disposition = RawSourceInputDisposition.INCLUDED,
        contentSha256 = digest,
    )

    protected fun available(
        vararg units: RawCompilationUnitInput,
    ): RawBuildOwnershipEvidence.Available = RawBuildOwnershipEvidence.Available(units.toList())

    protected fun validCompilationUnit(
        ownerId: String = "unit-main",
        moduleIdentity: String = ":application",
        moduleName: String = "application",
        sourceSetName: String = "jvmMain",
        variantName: String = "debug",
        generatedSourceRoots: Set<String> = setOf("misleading-layout/src/generated"),
        sourceSetRelationships: Set<RawSourceSetRelationshipInput> = emptySet(),
        compiler: RawCompilerInput = validCompiler(),
    ): RawCompilationUnitInput = RawCompilationUnitInput(
        ownerId = ownerId,
        moduleIdentity = moduleIdentity,
        moduleName = moduleName,
        sourceSetName = sourceSetName,
        variantName = variantName,
        sourceRoots = setOf("misleading-layout/src"),
        declarations = listOf(
            RawOwnedDeclarationInput(
                fullyQualifiedName = "sample.Application",
                path = "misleading-layout/src/Application.kt",
            ),
        ),
        families = setOf("sample.Application"),
        generatedSourceRoots = generatedSourceRoots,
        sourceSetRelationships = sourceSetRelationships,
        compiler = compiler,
    )

    protected fun validCompiler(): RawCompilerInput = RawCompilerInput(
        compilerVersion = "2.3.0",
        languageVersion = "2.3",
        apiVersion = "2.3",
        languageSettings = mapOf("progressive" to "true"),
        compilerImplementation = resolvedArtifact(
            component = "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.0",
            contentSha256 = DIGEST_A,
        ),
        toolchain = RawCompilerToolchainInput(
            targetPlatform = "jvm",
            version = "21.0.2",
            vendor = "example-vendor",
            implementation = "example-jdk",
            contentSha256 = DIGEST_A,
        ),
        compilerOptions = listOf(compilerOption("-progressive"), compilerOption("-jvm-target=21")),
        resolvedDependencies = listOf(resolvedArtifact()),
        compilerPlugins = listOf(compilerPlugin()),
    )

    protected fun compilerOption(token: String): RawCompilerOptionInput = RawCompilerOptionInput(token)

    protected fun resolvedArtifact(
        component: String = "org.example:library:1.0",
        selectedVariant: String = "runtimeElements",
        contentKind: ArtifactContentKind = ArtifactContentKind.FILE,
        contentSha256: String? = DIGEST_A,
    ): RawResolvedArtifactInput = RawResolvedArtifactInput(
        componentIdentity = component,
        selectedVariantIdentity = selectedVariant,
        contentKind = contentKind,
        contentSha256 = contentSha256,
    )

    protected fun compilerPlugin(
        pluginId: String = "org.example.plugin",
        artifactSha256: String = DIGEST_A,
        options: List<String>? = listOf("enabled=true"),
    ): RawCompilerPluginInput = RawCompilerPluginInput(
        pluginId = pluginId,
        classpath = listOf(
            resolvedArtifact(
                component = "$pluginId:artifact:1.0",
                contentSha256 = artifactSha256,
            ),
        ),
        options = options?.map(::compilerOption),
    )

    protected fun sourceSetRelationship(
        kind: SourceSetRelationshipKind,
        targetCompilationUnitId: String,
    ): RawSourceSetRelationshipInput = RawSourceSetRelationshipInput(kind, targetCompilationUnitId)

    protected fun validBounds(): RawResourceBoundsInput = RawResourceBoundsInput(
        timeLimitMillis = 30_000,
        memoryLimitBytes = 512L * 1024L * 1024L,
        traversalDepthLimit = 32,
        pathLimit = 10_000,
        resultLimit = 1_000,
    )
}

internal const val NONEXISTENT_REVISION: String = "ffffffffffffffffffffffffffffffffffffffff"
internal const val DIGEST_A: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val DIGEST_B: String = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
internal const val DIGEST_C: String = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
