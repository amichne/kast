package support.plugin

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.jar.JarInputStream
import java.util.zip.ZipInputStream
import org.objectweb.asm.ClassReader

@JvmInline
internal value class IdeHostedPolicyId private constructor(val value: String) {
    companion object {
        val readOnlyClasspath = IdeHostedPolicyId("IDE_HOSTED_READ_ONLY_CLASSPATH")
    }
}

@JvmInline
internal value class IdeHostedPluginSizeCeiling private constructor(val value: Long) {
    companion object {
        val default = IdeHostedPluginSizeCeiling(80L * 1024L * 1024L)
    }
}

@JvmInline
internal value class IdeHostedPluginRoot private constructor(val value: String) {
    companion object {
        val canonical = IdeHostedPluginRoot("kast-indexer/lib/")
    }
}

internal val IDE_HOSTED_PLUGIN_POLICY_ID = IdeHostedPolicyId.readOnlyClasspath
internal val IDE_HOSTED_PLUGIN_SIZE_CEILING_BYTES = IdeHostedPluginSizeCeiling.default
internal val IDE_HOSTED_PLUGIN_ROOT = IdeHostedPluginRoot.canonical

@JvmInline
internal value class IdeHostedJarEntry private constructor(val value: String) :
    Comparable<IdeHostedJarEntry> {
    companion object {
        /**
         * Proof transition: raw ZIP entry name `String -> IdeHostedJarEntry`.
         *
         * Establishes one direct JAR child of the fixed plugin library root. Expected rejection is
         * [IdeHostedJarEntryResult.Rejected]. Raw names remain at the ZIP-reader boundary.
         */
        fun admit(raw: String): IdeHostedJarEntryResult = if (
            raw.startsWith(IDE_HOSTED_PLUGIN_ROOT.value) && raw.endsWith(".jar") &&
            raw.removePrefix(IDE_HOSTED_PLUGIN_ROOT.value).let {
                it.isNotEmpty() && '/' !in it && ".." !in it
            }
        ) {
            IdeHostedJarEntryResult.Complete(IdeHostedJarEntry(raw))
        } else {
            IdeHostedJarEntryResult.Rejected
        }
    }

    override fun compareTo(other: IdeHostedJarEntry): Int = value.compareTo(other.value)
}

internal sealed interface IdeHostedJarEntryResult {
    data class Complete(val entry: IdeHostedJarEntry) : IdeHostedJarEntryResult
    data object Rejected : IdeHostedJarEntryResult
}

@JvmInline
internal value class IdeHostedDigest private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: observed bytes `ByteArray -> IdeHostedDigest`.
         *
         * Establishes the lowercase SHA-256 identity of the exact bytes. This transition has no
         * expected failure. The digest primitive may be extracted only by the report task.
         */
        fun observe(bytes: ByteArray): IdeHostedDigest = IdeHostedDigest(
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            },
        )
    }
}

@JvmInline
internal value class IdeHostedByteSize private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: observed bytes `ByteArray -> IdeHostedByteSize`.
         *
         * Establishes an exact non-negative byte count derived from an in-memory payload. This
         * transition has no expected failure. The primitive may be extracted only by the report task.
         */
        fun observe(bytes: ByteArray): IdeHostedByteSize = IdeHostedByteSize(bytes.size.toLong())

        /**
         * Proof transition: plugin archive bytes `ByteArray -> IdeHostedArchiveSizeResult`.
         *
         * Establishes an exact size within [IDE_HOSTED_PLUGIN_SIZE_CEILING_BYTES], or the finite
         * `ARCHIVE_TOO_LARGE` rejection. Size extraction remains at the report boundary.
         */
        fun admitPluginArchive(bytes: ByteArray): IdeHostedArchiveSizeResult {
            val observed = bytes.size.toLong()
            return if (observed <= IDE_HOSTED_PLUGIN_SIZE_CEILING_BYTES.value) {
                IdeHostedArchiveSizeResult.Complete(IdeHostedByteSize(observed))
            } else {
                IdeHostedArchiveSizeResult.Rejected
            }
        }
    }
}

internal sealed interface IdeHostedArchiveSizeResult {
    data class Complete(val size: IdeHostedByteSize) : IdeHostedArchiveSizeResult
    data object Rejected : IdeHostedArchiveSizeResult
}

internal enum class IdePluginLayoutFailure {
    ARCHIVE_UNAVAILABLE,
    ARCHIVE_IO_FAILURE,
    ARCHIVE_TOO_LARGE,
    ARCHIVE_MALFORMED,
    INVALID_ARCHIVE_ENTRY,
    DUPLICATE_ARCHIVE_ENTRY,
    NONDETERMINISTIC_ENTRY_ORDER,
    NESTED_JAR_MALFORMED,
    CLASS_MALFORMED,
    CLASS_OWNER_MISMATCH,
    INTELLIJ_PLATFORM_CLASS,
    KOTLIN_PLATFORM_CLASS,
    GRADLE_PLATFORM_CLASS,
    JBR_CLASS,
    BOOTSTRAP_CLASS,
    MUTATION_CLASS,
    TOPOLOGY_CLASS,
    JDBC_CLASS,
    RUNTIME_ACQUISITION_CLASS,
    PROCESS_LAUNCH_REFERENCE,
    FORBIDDEN_NATIVE_OR_RUNTIME_RESOURCE,
    REPORT_WRITE_FAILURE,
}

@JvmInline
internal value class IdeHostedClassOwner private constructor(val value: String) :
    Comparable<IdeHostedClassOwner> {
    companion object {
        /**
         * Proof transition: class entry and declared owner `(String, String) -> IdeHostedClassOwner`.
         *
         * Establishes exact entry-to-owner identity and exclusion of forbidden class definitions.
         * Expected rejection is the finite [IdePluginLayoutFailure]. The owner primitive may be
         * extracted only by the report task.
         */
        fun admit(entry: String, declaredOwner: String): IdeHostedClassOwnerResult {
            val definition = when (val admitted = AdmittedClassDefinition.admit(declaredOwner)) {
                is ClassDefinitionAdmission.Complete -> admitted.definition
                is ClassDefinitionAdmission.Rejected -> return IdeHostedClassOwnerResult.Rejected(
                    admitted.failure,
                )
            }
            val expectedOwner = entry.removePrefix("META-INF/versions/")
                .let { if (it.firstOrNull()?.isDigit() == true) it.substringAfter('/') else it }
                .removeSuffix(".class")
            return if (expectedOwner == definition.value) {
                IdeHostedClassOwnerResult.Complete(IdeHostedClassOwner(definition.value))
            } else {
                IdeHostedClassOwnerResult.Rejected(IdePluginLayoutFailure.CLASS_OWNER_MISMATCH)
            }
        }
    }

    override fun compareTo(other: IdeHostedClassOwner): Int = value.compareTo(other.value)
}

internal sealed interface IdeHostedClassOwnerResult {
    data class Complete(val owner: IdeHostedClassOwner) : IdeHostedClassOwnerResult
    data class Rejected(val failure: IdePluginLayoutFailure) : IdeHostedClassOwnerResult
}

internal class VerifiedHostedClass private constructor(
    val owner: IdeHostedClassOwner,
    val references: VerifiedBytecodeReferences,
) {
    companion object {
        /** `(String, ByteArray) -> VerifiedHostedClass`: proves exact owner and reference policy;
         * rejects with finite [IdePluginLayoutFailure]; raw class data stays in the scanner. */
        internal fun admit(entry: String, bytes: ByteArray): ClassScan = scanClass(entry, bytes) {
            owner, references -> VerifiedHostedClass(owner, references)
        }
    }
}

internal class VerifiedLayoutJar private constructor(
    val entry: IdeHostedJarEntry,
    val digest: IdeHostedDigest,
    val sizeBytes: IdeHostedByteSize,
    classes: List<VerifiedHostedClass>,
    nestedEntries: List<AdmittedNestedJarEntry>,
) {
    private val verifiedClasses = java.util.Collections.unmodifiableList(classes.toList())
    val classOwners: List<IdeHostedClassOwner> = java.util.Collections.unmodifiableList(
        verifiedClasses.map { it.owner },
    )
    private val admittedEntries = java.util.Collections.unmodifiableList(nestedEntries.toList())

    companion object {
        /** `(IdeHostedJarEntry, ByteArray) -> VerifiedLayoutJar`: proves unique entries, verified
         * classes, digest, and size; rejects with [IdePluginLayoutFailure]; raw bytes stay here. */
        internal fun admit(entry: IdeHostedJarEntry, bytes: ByteArray): NestedJarScan =
            scanNestedJar(entry, bytes) { digest, size, classes, entries ->
                VerifiedLayoutJar(entry, digest, size, classes, entries)
            }
    }
}

internal class VerifiedIdePluginLayout private constructor(
    val archiveDigest: IdeHostedDigest,
    val archiveSizeBytes: IdeHostedByteSize,
    jars: List<VerifiedLayoutJar>,
) {
    val jars: List<VerifiedLayoutJar> = java.util.Collections.unmodifiableList(jars.toList())

    companion object {
        /** `ByteArray -> VerifiedIdePluginLayout`: proves a non-empty, bounded, ordered, verified
         * classpath; rejects with [IdePluginLayoutFailure]; raw ZIP bytes stay in the scanner. */
        internal fun admit(bytes: ByteArray): IdePluginLayoutResult = scanIdePluginLayout(bytes) {
            digest, size, jars -> VerifiedIdePluginLayout(digest, size, jars)
        }
    }
}

internal sealed interface IdePluginLayoutResult {
    data class Complete(val layout: VerifiedIdePluginLayout) : IdePluginLayoutResult
    data class Rejected(val failure: IdePluginLayoutFailure) : IdePluginLayoutResult
}

/**
 * Proof transition: `RepositoryBoundPluginArchive -> VerifiedIdePluginLayout`.
 *
 * Establishes an ordered, bounded nested-JAR classpath whose definitions, effect references, and
 * resources contain no forbidden implementation. Expected rejection is [IdePluginLayoutFailure].
 * Raw archive and class bytes remain at the filesystem and scanner boundaries.
 */
internal fun admitIdePluginLayout(archive: RepositoryBoundPluginArchive): IdePluginLayoutResult =
    VerifiedIdePluginLayout.admit(archive.copyContentForScanner())

/**
 * Proof transition: immutable fixture content `IdeHostedArchiveContent -> VerifiedIdePluginLayout`.
 *
 * Establishes the same layout invariants as repository archive admission for a fixed negative
 * fixture. Expected rejection is [IdePluginLayoutFailure]. Raw bytes remain inside copied content.
 */
internal fun admitIdePluginLayoutFixture(
    archive: IdeHostedArchiveContent,
): IdePluginLayoutResult = VerifiedIdePluginLayout.admit(archive.copyForScanner())

/** `ByteArray -> VerifiedIdePluginLayout`: proves size, entry order, and nested-JAR admission;
 * rejects with [IdePluginLayoutFailure]; raw ZIP bytes do not escape this scanner. */
private fun scanIdePluginLayout(
    archive: ByteArray,
    complete: (IdeHostedDigest, IdeHostedByteSize, List<VerifiedLayoutJar>) -> VerifiedIdePluginLayout,
): IdePluginLayoutResult {
    val archiveSize = when (val admitted = IdeHostedByteSize.admitPluginArchive(archive)) {
        is IdeHostedArchiveSizeResult.Complete -> admitted.size
        IdeHostedArchiveSizeResult.Rejected -> {
            return rejected(IdePluginLayoutFailure.ARCHIVE_TOO_LARGE)
        }
    }
    val jars = mutableListOf<VerifiedLayoutJar>()
    val names = mutableSetOf<IdeHostedJarEntry>()
    try {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var raw = zip.nextEntry
            while (raw != null) {
                if (raw.isDirectory) return rejected(IdePluginLayoutFailure.INVALID_ARCHIVE_ENTRY)
                val entry = when (val admitted = IdeHostedJarEntry.admit(raw.name)) {
                    is IdeHostedJarEntryResult.Complete -> admitted.entry
                    IdeHostedJarEntryResult.Rejected -> {
                        return rejected(IdePluginLayoutFailure.INVALID_ARCHIVE_ENTRY)
                    }
                }
                if (!names.add(entry)) {
                    return rejected(IdePluginLayoutFailure.DUPLICATE_ARCHIVE_ENTRY)
                }
                when (val scanned = VerifiedLayoutJar.admit(entry, zip.readBytes())) {
                    is NestedJarScan.Complete -> jars += scanned.jar
                    is NestedJarScan.Rejected -> return rejected(scanned.failure)
                }
                raw = zip.nextEntry
            }
        }
    } catch (_: Exception) {
        return rejected(IdePluginLayoutFailure.ARCHIVE_MALFORMED)
    }
    if (jars.isEmpty()) return rejected(IdePluginLayoutFailure.ARCHIVE_MALFORMED)
    if (jars.map { it.entry } != jars.map { it.entry }.sorted()) {
        return rejected(IdePluginLayoutFailure.NONDETERMINISTIC_ENTRY_ORDER)
    }
    return IdePluginLayoutResult.Complete(
        complete(IdeHostedDigest.observe(archive), archiveSize, jars),
    )
}

internal sealed interface NestedJarScan {
    data class Complete(val jar: VerifiedLayoutJar) : NestedJarScan
    data class Rejected(val failure: IdePluginLayoutFailure) : NestedJarScan
}

/** `(IdeHostedJarEntry, ByteArray) -> VerifiedLayoutJar`: proves unique entries and classes;
 * rejects with [IdePluginLayoutFailure]; raw JAR bytes do not escape this scanner. */
private fun scanNestedJar(
    entry: IdeHostedJarEntry,
    bytes: ByteArray,
    complete: (
        IdeHostedDigest,
        IdeHostedByteSize,
        List<VerifiedHostedClass>,
        List<AdmittedNestedJarEntry>,
    ) -> VerifiedLayoutJar,
): NestedJarScan {
    val classes = mutableListOf<VerifiedHostedClass>()
    val entries = mutableListOf<AdmittedNestedJarEntry>()
    val names = mutableSetOf<AdmittedNestedJarEntry>()
    try {
        JarInputStream(ByteArrayInputStream(bytes)).use { jar ->
            var raw = jar.nextJarEntry
            while (raw != null) {
                if (!raw.isDirectory) {
                    val entryResult = when (val admitted = AdmittedNestedJarEntry.admit(raw.name)) {
                        is NestedJarEntryAdmission.Complete -> admitted.entry
                        is NestedJarEntryAdmission.Rejected -> {
                            return NestedJarScan.Rejected(admitted.failure)
                        }
                    }
                    if (!names.add(entryResult)) {
                        return NestedJarScan.Rejected(
                            IdePluginLayoutFailure.DUPLICATE_ARCHIVE_ENTRY,
                        )
                    }
                    entries += entryResult
                    if (entryResult.kind == NestedJarEntryKind.CLASS_FILE) {
                        when (val scanned = VerifiedHostedClass.admit(
                            entryResult.identity.value,
                            jar.readBytes(),
                        )) {
                            is ClassScan.Complete -> classes += scanned.verifiedClass
                            is ClassScan.Rejected -> return NestedJarScan.Rejected(scanned.failure)
                        }
                    }
                }
                raw = jar.nextJarEntry
            }
        }
    } catch (_: Exception) {
        return NestedJarScan.Rejected(IdePluginLayoutFailure.NESTED_JAR_MALFORMED)
    }
    return NestedJarScan.Complete(
        complete(
            IdeHostedDigest.observe(bytes),
            IdeHostedByteSize.observe(bytes),
            classes.sortedBy { it.owner },
            entries.sorted(),
        ),
    )
}

internal sealed interface ClassScan {
    data class Complete(val verifiedClass: VerifiedHostedClass) : ClassScan
    data class Rejected(val failure: IdePluginLayoutFailure) : ClassScan
}

/** `(String, ByteArray) -> VerifiedHostedClass`: proves exact owner and bytecode references;
 * rejects with [IdePluginLayoutFailure]; raw class bytes remain at this ASM boundary. */
private fun scanClass(
    entry: String,
    bytes: ByteArray,
    complete: (IdeHostedClassOwner, VerifiedBytecodeReferences) -> VerifiedHostedClass,
): ClassScan = try {
    val reader = ClassReader(bytes)
    val owner = when (val admitted = IdeHostedClassOwner.admit(entry, reader.className)) {
        is IdeHostedClassOwnerResult.Complete -> admitted.owner
        is IdeHostedClassOwnerResult.Rejected -> return ClassScan.Rejected(admitted.failure)
    }
    when (val result = VerifiedBytecodeReferences.inspect(reader)) {
        is BytecodePolicyResult.Complete -> ClassScan.Complete(
            complete(owner, result.references),
        )
        is BytecodePolicyResult.Rejected -> ClassScan.Rejected(result.failure)
    }
} catch (_: Exception) {
    ClassScan.Rejected(IdePluginLayoutFailure.CLASS_MALFORMED)
}

private fun rejected(failure: IdePluginLayoutFailure) = IdePluginLayoutResult.Rejected(failure)
