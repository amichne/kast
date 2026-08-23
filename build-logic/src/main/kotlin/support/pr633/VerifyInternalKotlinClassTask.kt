package support.pr633

import java.io.File
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader

/**
 * Proof transition: `Kotlin source + complete compiled main output -> VerifiedInternalKotlinClass`.
 *
 * Establishes one exact source/package/class identity, one corresponding compiled class, matching
 * Kotlin metadata identity, and `Visibility.INTERNAL`. Expected failure is the closed
 * `InternalKotlinClassFailure`; raw source, class bytes, and metadata fields remain at this Gradle
 * boundary.
 */
@CacheableTask
abstract class VerifyInternalKotlinClassTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection
    @get:Input
    abstract val expectedPackageName: Property<String>
    @get:Input
    abstract val expectedSimpleClassName: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /**
     * Proof transition: `configured task inputs -> VerifiedInternalKotlinClass report`.
     *
     * Consumes each stronger refinement in order and materializes the final identity and visibility.
     * Closed expected failures are projected to Gradle only at this outer task boundary.
     */
    @TaskAction
    fun verify() {
        val expected = ExpectedKotlinClass.parse(
            expectedPackageName.get(),
            expectedSimpleClassName.get(),
        ).atGradleBoundary()
        val rawSource = sourceFile.get().asFile.let { file ->
            RawKotlinSourceFile(file.name, file.readText())
        }
        val rawClasses = classDirectories.asFileTree.matching { include("**/*.class") }.files
            .sortedBy(File::getPath)
            .map { file -> RawCompiledClassFile(file.path, file.readBytes()) }
        val source = SourceBoundKotlinClass.refine(
            expected,
            rawSource,
        ).atGradleBoundary()
        val compiled = VerifiedInternalKotlinClass.refine(
            source,
            rawClasses,
        ).atGradleBoundary()
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "internalName=${compiled.internalName}\n" +
                "sourceFile=${compiled.sourceFileName}\n" +
                "visibility=internal\n",
        )
    }
}

private sealed interface InternalKotlinClassResult<out T> {
    data class Proven<T>(val value: T) : InternalKotlinClassResult<T>
    data class Rejected(val failure: InternalKotlinClassFailure) :
        InternalKotlinClassResult<Nothing>
}
private sealed interface InternalKotlinClassFailure {
    data class InvalidPackageName(val value: String) : InternalKotlinClassFailure
    data class InvalidSimpleClassName(val value: String) : InternalKotlinClassFailure
    data class UnexpectedSourceFileName(val expected: String, val observed: String) :
        InternalKotlinClassFailure
    data class UnexpectedSourcePackage(val expected: String, val observed: List<String>) :
        InternalKotlinClassFailure
    data class MissingSourceDeclaration(val simpleName: String) : InternalKotlinClassFailure
    data class MalformedClassFile(val path: String, val detail: String) : InternalKotlinClassFailure
    data class MissingCompiledClass(val internalName: String) : InternalKotlinClassFailure
    data class DuplicateCompiledClass(val internalName: String, val paths: List<String>) :
        InternalKotlinClassFailure
    data class MissingKotlinMetadata(val internalName: String) : InternalKotlinClassFailure
    data class DuplicateKotlinMetadata(val internalName: String) : InternalKotlinClassFailure
    data class MalformedKotlinMetadata(
        val internalName: String,
        val cause: KotlinMetadataMalformedCause,
    ) :
        InternalKotlinClassFailure
    data class UnexpectedMetadataKind(val internalName: String) : InternalKotlinClassFailure
    data class MetadataIdentityMismatch(val expected: String, val observed: String) :
        InternalKotlinClassFailure
    data class SourceBindingMismatch(val expected: String, val observed: List<String>) :
        InternalKotlinClassFailure
    data class VisibilityMismatch(val internalName: String, val observed: Visibility) :
        InternalKotlinClassFailure
}
private sealed interface KotlinMetadataMalformedCause {
    data class FieldShape(val failure: RawKotlinMetadataFailure) : KotlinMetadataMalformedCause
    data class StrictParserRejection(val detail: String) : KotlinMetadataMalformedCause
}
private class ExpectedKotlinClass private constructor(
    val packageName: String,
    val simpleName: String,
) {
    val internalName: String = packageName.replace('.', '/') + "/" + simpleName
    val sourceFileName: String = "$simpleName.kt"

    companion object {
        /**
         * Proof transition: `(String, String) -> ExpectedKotlinClass`.
         *
         * Establishes valid Kotlin package and simple class identifiers and derives every source
         * and JVM identity from that single aggregate. Expected failure is the closed invalid-name
         * subset of `InternalKotlinClassFailure`; raw names enter only here.
         */
        fun parse(
            packageName: String,
            simpleName: String,
        ): InternalKotlinClassResult<ExpectedKotlinClass> = when {
            !packageName.matches(PACKAGE_NAME) -> InternalKotlinClassResult.Rejected(
                InternalKotlinClassFailure.InvalidPackageName(packageName),
            )
            !simpleName.matches(SIMPLE_NAME) -> InternalKotlinClassResult.Rejected(
                InternalKotlinClassFailure.InvalidSimpleClassName(simpleName),
            )
            else -> InternalKotlinClassResult.Proven(
                ExpectedKotlinClass(packageName, simpleName),
            )
        }
    }
}
private class SourceBoundKotlinClass private constructor(
    val expected: ExpectedKotlinClass,
    val sourceFileName: String,
) {
    companion object {
        /**
         * Proof transition: `(ExpectedKotlinClass, RawKotlinSourceFile) -> SourceBoundKotlinClass`.
         *
         * Establishes the expected source filename, exact package declaration, and an unambiguous
         * class declaration token. Compiled Kotlin metadata remains the visibility authority.
         * Expected failure is the closed source subset of `InternalKotlinClassFailure`.
         */
        fun refine(
            expected: ExpectedKotlinClass,
            sourceFile: RawKotlinSourceFile,
        ): InternalKotlinClassResult<SourceBoundKotlinClass> {
            if (sourceFile.name != expected.sourceFileName) {
                return InternalKotlinClassResult.Rejected(
                    InternalKotlinClassFailure.UnexpectedSourceFileName(
                        expected.sourceFileName,
                        sourceFile.name,
                    ),
                )
            }
            val packages = PACKAGE_DECLARATION.findAll(sourceFile.content).map { match ->
                match.groupValues[1]
            }.toList()
            if (packages != listOf(expected.packageName)) {
                return InternalKotlinClassResult.Rejected(
                    InternalKotlinClassFailure.UnexpectedSourcePackage(
                        expected.packageName,
                        packages,
                    ),
                )
            }
            val declarations = Regex(
                "(?m)^\\s*(?:[A-Za-z]+\\s+)*class\\s+" +
                    Regex.escape(expected.simpleName) + "(?=\\s|\\(|<|\\{)",
            ).findAll(sourceFile.content).count()
            return if (declarations == 1) {
                InternalKotlinClassResult.Proven(
                    SourceBoundKotlinClass(expected, sourceFile.name),
                )
            } else {
                InternalKotlinClassResult.Rejected(
                    InternalKotlinClassFailure.MissingSourceDeclaration(expected.simpleName),
                )
            }
        }
    }
}
private class VerifiedInternalKotlinClass private constructor(
    val internalName: String,
    val sourceFileName: String,
) {
    companion object {
        /**
         * Proof transition: `(SourceBoundKotlinClass, List<RawCompiledClassFile>) ->
         * VerifiedInternalKotlinClass`.
         *
         * Establishes exactly one compiled identity across the complete main output, exact source
         * association, Kotlin class metadata identity, and `Visibility.INTERNAL`. Expected failure
         * is the closed compiled/metadata subset of `InternalKotlinClassFailure`.
         */
        fun refine(
            source: SourceBoundKotlinClass,
            classFiles: List<RawCompiledClassFile>,
        ): InternalKotlinClassResult<VerifiedInternalKotlinClass> {
            val parsed = mutableListOf<ParsedClassFile>()
            classFiles.forEach { file ->
                try {
                    val reader = ClassReader(file.bytes)
                    if (reader.className == source.expected.internalName) {
                        parsed += ParsedClassFile.parse(file, reader)
                    }
                } catch (failure: RuntimeException) {
                    return InternalKotlinClassResult.Rejected(
                        InternalKotlinClassFailure.MalformedClassFile(
                            file.path,
                            failure.toString(),
                        ),
                    )
                }
            }
            if (parsed.isEmpty()) {
                return InternalKotlinClassResult.Rejected(
                    InternalKotlinClassFailure.MissingCompiledClass(source.expected.internalName),
                )
            }
            if (parsed.size > 1) {
                return InternalKotlinClassResult.Rejected(
                    InternalKotlinClassFailure.DuplicateCompiledClass(
                        source.expected.internalName,
                        parsed.map(ParsedClassFile::path),
                    ),
                )
            }
            val candidate = parsed.single()
            if (candidate.sourceFiles != listOf(source.sourceFileName)) {
                return InternalKotlinClassResult.Rejected(
                    InternalKotlinClassFailure.SourceBindingMismatch(
                        source.sourceFileName,
                        candidate.sourceFiles,
                    ),
                )
            }
            if (candidate.metadata.size != 1) {
                return InternalKotlinClassResult.Rejected(
                    if (candidate.metadata.isEmpty()) {
                        InternalKotlinClassFailure.MissingKotlinMetadata(source.expected.internalName)
                    } else {
                        InternalKotlinClassFailure.DuplicateKotlinMetadata(source.expected.internalName)
                    },
                )
            }
            val admittedMetadata = when (val result = candidate.metadata.single().admitAnnotation()) {
                is RawKotlinMetadataAdmission.Proven -> result.value
                is RawKotlinMetadataAdmission.Rejected ->
                    return InternalKotlinClassResult.Rejected(
                        InternalKotlinClassFailure.MalformedKotlinMetadata(
                            source.expected.internalName,
                            KotlinMetadataMalformedCause.FieldShape(result.failure),
                        ),
                    )
            }
            val metadata = try {
                KotlinClassMetadata.readStrict(admittedMetadata.annotation)
            } catch (failure: RuntimeException) {
                return InternalKotlinClassResult.Rejected(
                    InternalKotlinClassFailure.MalformedKotlinMetadata(
                        source.expected.internalName,
                        KotlinMetadataMalformedCause.StrictParserRejection(failure.toString()),
                    ),
                )
            }
            if (metadata !is KotlinClassMetadata.Class) {
                return InternalKotlinClassResult.Rejected(
                    InternalKotlinClassFailure.UnexpectedMetadataKind(source.expected.internalName),
                )
            }
            if (metadata.kmClass.name != source.expected.internalName) {
                return InternalKotlinClassResult.Rejected(
                    InternalKotlinClassFailure.MetadataIdentityMismatch(
                        source.expected.internalName,
                        metadata.kmClass.name,
                    ),
                )
            }
            return if (metadata.kmClass.visibility == Visibility.INTERNAL) {
                InternalKotlinClassResult.Proven(
                    VerifiedInternalKotlinClass(
                        source.expected.internalName,
                        source.sourceFileName,
                    ),
                )
            } else {
                InternalKotlinClassResult.Rejected(
                    InternalKotlinClassFailure.VisibilityMismatch(
                        source.expected.internalName,
                        metadata.kmClass.visibility,
                    ),
                )
            }
        }
    }
}
private data class ParsedClassFile(
    val path: String,
    val sourceFiles: List<String>,
    val metadata: List<RawKotlinMetadata>,
) {
    companion object {
        /**
         * Projection transition: `(RawCompiledClassFile, ClassReader) -> ParsedClassFile`.
         *
         * Associates already parsed class evidence with its boundary path. Identity, source,
         * metadata kind, and visibility remain subject to the owning refinement.
         */
        fun parse(file: RawCompiledClassFile, reader: ClassReader): ParsedClassFile {
            val evidence = projectRawKotlinClassEvidence(reader)
            return ParsedClassFile(file.path, evidence.sourceFiles, evidence.metadata)
        }
    }
}
/**
 * Boundary projection: `InternalKotlinClassFailure -> String`.
 *
 * Renders the closed expected failure set only where Gradle requires textual diagnostics.
 */
private fun InternalKotlinClassFailure.render(): String = when (this) {
    is InternalKotlinClassFailure.InvalidPackageName -> "invalid Kotlin package '$value'"
    is InternalKotlinClassFailure.InvalidSimpleClassName -> "invalid Kotlin class '$value'"
    is InternalKotlinClassFailure.UnexpectedSourceFileName -> "expected source file '$expected', observed '$observed'"
    is InternalKotlinClassFailure.UnexpectedSourcePackage -> "expected source package '$expected', observed $observed"
    is InternalKotlinClassFailure.MissingSourceDeclaration -> "source does not contain one class declaration for $simpleName"
    is InternalKotlinClassFailure.MalformedClassFile -> "malformed class file '$path': $detail"
    is InternalKotlinClassFailure.MissingCompiledClass -> "compiled class '$internalName' is missing"
    is InternalKotlinClassFailure.DuplicateCompiledClass ->
        "compiled class '$internalName' is duplicated across $paths"
    is InternalKotlinClassFailure.MissingKotlinMetadata -> "compiled class '$internalName' has no Kotlin metadata"
    is InternalKotlinClassFailure.DuplicateKotlinMetadata -> "compiled class '$internalName' has duplicate Kotlin metadata"
    is InternalKotlinClassFailure.MalformedKotlinMetadata ->
        "compiled class '$internalName' has malformed Kotlin metadata: ${cause.render()}"
    is InternalKotlinClassFailure.UnexpectedMetadataKind -> "compiled class '$internalName' is not Kotlin class metadata"
    is InternalKotlinClassFailure.MetadataIdentityMismatch ->
        "compiled Kotlin metadata names '$observed', not '$expected'"
    is InternalKotlinClassFailure.SourceBindingMismatch ->
        "compiled class source is $observed, not '$expected'"
    is InternalKotlinClassFailure.VisibilityMismatch ->
        "compiled Kotlin class '$internalName' visibility is $observed, not INTERNAL"
}

/**
 * Boundary projection: `KotlinMetadataMalformedCause -> String`.
 *
 * Renders the closed field-shape or strict-parser rejection only at the Gradle failure boundary.
 */
private fun KotlinMetadataMalformedCause.render(): String = when (this) {
    is KotlinMetadataMalformedCause.FieldShape -> failure.render()
    is KotlinMetadataMalformedCause.StrictParserRejection -> detail
}
/**
 * Proof transition: `InternalKotlinClassResult<T> -> T` at the Gradle task boundary.
 *
 * Preserves successful refinements and projects the closed failure set into Gradle's exception
 * protocol. Core parsers and refinements do not call this extractor.
 */
private fun <T> InternalKotlinClassResult<T>.atGradleBoundary(): T = when (this) {
    is InternalKotlinClassResult.Proven -> value
    is InternalKotlinClassResult.Rejected -> error(failure.render())
}
private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
private val SIMPLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val PACKAGE_DECLARATION = Regex(
    "(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*$",
)
