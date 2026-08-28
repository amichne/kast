package io.github.amichne.kast.workspace.intellij.read

import java.io.DataInputStream
import java.io.IOException
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal sealed interface DetachedModelClassContractFailure {
    data class ResourceMissing(val resource: String) : DetachedModelClassContractFailure
    data class ClassRejected(val resource: String) : DetachedModelClassContractFailure
    data class ClassVersionMismatch(
        val resource: String,
        val expected: Int,
        val observed: Int,
    ) : DetachedModelClassContractFailure
    data class MissingClassReference(val className: String) : DetachedModelClassContractFailure
    data class MissingMember(val member: DetachedModelMemberReference) : DetachedModelClassContractFailure
    data class ForbiddenMember(val member: DetachedModelMemberReference) : DetachedModelClassContractFailure
    data class PublicModelMethodSetMismatch(
        val expected: List<String>,
        val observed: List<String>,
    ) : DetachedModelClassContractFailure
    data class ClassFingerprintMismatch(
        val resource: String,
        val expected: String,
        val observed: String,
    ) : DetachedModelClassContractFailure
}

internal data class DetachedModelMemberReference(
    val owner: String,
    val name: String,
    val descriptor: String,
)

/** Byte-only IDEA 262 contract for the live detached-model adapter. */
internal object DetachedModelClassContract {
    fun verify(): List<DetachedModelClassContractFailure> {
        val reads = EXPECTED_FINGERPRINTS.keys.associateWith(::readClass)
        val readFailures = reads.mapNotNull { (resource, result) ->
            when (result) {
                ClassRead.Missing -> DetachedModelClassContractFailure.ResourceMissing(resource)
                ClassRead.Rejected -> DetachedModelClassContractFailure.ClassRejected(resource)
                is ClassRead.Admitted -> null
            }
        }
        if (readFailures.isNotEmpty()) return readFailures

        val views = reads.mapValues { (_, result) -> (result as ClassRead.Admitted).view }
        val main = views.getValue(MAIN_RESOURCE)
        val allMembers = views.values.flatMapTo(linkedSetOf(), DetachedModelClassView::members)
        return buildList {
            val publicModelMethods = DetachedIdeWorkspaceModel::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }
                .map { method -> method.name.substringBefore('-') }
                .sorted()
            if (publicModelMethods != EXPECTED_PUBLIC_MODEL_METHODS) {
                add(
                    DetachedModelClassContractFailure.PublicModelMethodSetMismatch(
                        EXPECTED_PUBLIC_MODEL_METHODS,
                        publicModelMethods,
                    ),
                )
            }
            views.forEach { (resource, view) ->
                if (view.majorVersion != JAVA_21_CLASS_VERSION) {
                    add(
                        DetachedModelClassContractFailure.ClassVersionMismatch(
                            resource,
                            JAVA_21_CLASS_VERSION,
                            view.majorVersion,
                        ),
                    )
                }
                val expected = EXPECTED_FINGERPRINTS.getValue(resource)
                if (view.fingerprint != expected) {
                    add(
                        DetachedModelClassContractFailure.ClassFingerprintMismatch(
                            resource,
                            expected,
                            view.fingerprint,
                        ),
                    )
                }
            }
            REQUIRED_CLASS_REFERENCES.filterNot(main.classNames::contains).forEach { missing ->
                add(DetachedModelClassContractFailure.MissingClassReference(missing))
            }
            REQUIRED_MEMBERS.filterNot(main.members::contains).forEach { missing ->
                add(DetachedModelClassContractFailure.MissingMember(missing))
            }
            allMembers.filter(::isForbidden).sortedWith(MEMBER_ORDER).forEach { forbidden ->
                add(DetachedModelClassContractFailure.ForbiddenMember(forbidden))
            }
        }
    }

    private fun readClass(resource: String): ClassRead {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { stream ->
            stream.readAllBytes()
        } ?: return ClassRead.Missing
        return try {
            val parsed = DataInputStream(bytes.inputStream()).use(::parseClass)
                ?: return ClassRead.Rejected
            ClassRead.Admitted(
                parsed.copy(
                    fingerprint = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes),
                    ),
                ),
            )
        } catch (_: IOException) {
            ClassRead.Rejected
        } catch (_: IllegalArgumentException) {
            ClassRead.Rejected
        } catch (_: IndexOutOfBoundsException) {
            ClassRead.Rejected
        } catch (_: ClassCastException) {
            ClassRead.Rejected
        }
    }

    private fun parseClass(input: DataInputStream): DetachedModelClassView? {
        if (input.readInt() != CLASS_MAGIC) return null
        input.readUnsignedShort()
        val majorVersion = input.readUnsignedShort()
        val entries = arrayOfNulls<DetachedModelPoolEntry>(input.readUnsignedShort())
        var index = 1
        while (index < entries.size) {
            when (input.readUnsignedByte()) {
                1 -> entries[index] = DetachedModelPoolEntry.Utf8(input.readUTF())
                3, 4 -> input.skipNBytes(4).also { entries[index] = DetachedModelPoolEntry.Other }
                5, 6 -> {
                    input.skipNBytes(8)
                    entries[index] = DetachedModelPoolEntry.Other
                    index += 1
                }
                7 -> entries[index] = DetachedModelPoolEntry.ClassName(input.readUnsignedShort())
                8, 16, 19, 20 ->
                    input.skipNBytes(2).also { entries[index] = DetachedModelPoolEntry.Other }
                9 -> entries[index] = DetachedModelPoolEntry.Field(
                    input.readUnsignedShort(),
                    input.readUnsignedShort(),
                )
                10, 11 -> entries[index] = DetachedModelPoolEntry.Method(
                    input.readUnsignedShort(),
                    input.readUnsignedShort(),
                )
                12 -> entries[index] = DetachedModelPoolEntry.NameAndType(
                    input.readUnsignedShort(),
                    input.readUnsignedShort(),
                )
                15 -> input.skipNBytes(3).also { entries[index] = DetachedModelPoolEntry.Other }
                17, 18 -> input.skipNBytes(4).also { entries[index] = DetachedModelPoolEntry.Other }
                else -> return null
            }
            index += 1
        }

        fun utf8(at: Int) = (entries[at] as DetachedModelPoolEntry.Utf8).value
        fun className(at: Int): String {
            val entry = entries[at] as DetachedModelPoolEntry.ClassName
            return utf8(entry.nameIndex)
        }
        val classNames = entries.filterIsInstance<DetachedModelPoolEntry.ClassName>()
            .mapTo(linkedSetOf()) { entry -> utf8(entry.nameIndex) }
        val members = entries.filterIsInstance<DetachedModelPoolEntry.Method>()
            .mapTo(linkedSetOf()) { method ->
                val nameAndType = entries[method.nameAndTypeIndex]
                    as DetachedModelPoolEntry.NameAndType
                DetachedModelMemberReference(
                    className(method.ownerIndex),
                    utf8(nameAndType.nameIndex),
                    utf8(nameAndType.descriptorIndex),
                )
            }
        return DetachedModelClassView(majorVersion, classNames, members, "")
    }

    private fun isForbidden(member: DetachedModelMemberReference): Boolean {
        val name = member.name.lowercase()
        return when {
            member.owner == READ_ACTION && member.name in BLOCKING_READ_METHODS -> true
            member.owner.endsWith("NonBlockingReadAction") &&
                member.name == "executeSynchronously" -> true
            member.owner == APPLICATION && member.name == "runReadAction" -> true
            member in WHOLE_CLASSPATH_MATERIALIZERS -> true
            member.owner.endsWith("DumbService") && member.name in SMART_WAIT_METHODS -> true
            member.owner.startsWith("com/intellij/openapi/externalSystem/") &&
                DESTRUCTIVE_EXTERNAL_SYSTEM_VERBS.any(name::startsWith) -> true
            member.owner.startsWith("com/intellij/openapi/vfs/") &&
                FORBIDDEN_VFS_METHODS.any(name::contains) -> true
            member.owner == "java/nio/file/Files" &&
                FORBIDDEN_FILES_METHODS.any(name::startsWith) -> true
            member.owner.startsWith("java/io/") && member.owner != "java/io/Serializable" -> true
            member.owner == "java/security/MessageDigest" -> true
            member.owner.startsWith("com/google/common/hash/") -> true
            member.owner.startsWith("kotlin/io/") -> true
            member.owner.startsWith("kotlin/io/path/") -> true
            member.owner == "java/lang/Thread" && member.name == "sleep" -> true
            member.owner == "java/lang/Object" && member.name == "wait" -> true
            member.owner == "java/util/concurrent/Future" && member.name == "get" -> true
            member.owner == "java/util/concurrent/CompletableFuture" &&
                member.name in setOf("get", "join") -> true
            name == "runblocking" -> true
            else -> false
        }
    }

    private fun member(owner: String, name: String, descriptor: String) =
        DetachedModelMemberReference(owner, name, descriptor)

    private const val LOCAL = "io/github/amichne/kast/workspace/intellij/read/"
    private const val MAIN_RESOURCE = LOCAL + "LiveDetachedModelCapture.class"
    private const val MAPPINGS_RESOURCE = LOCAL + "LiveDetachedModelCapture\$WhenMappings.class"
    private const val READ_ACTION = "com/intellij/openapi/application/ReadAction"
    private const val APPLICATION = "com/intellij/openapi/application/Application"
    private const val PROJECT = "Lcom/intellij/openapi/project/Project;"
    private const val CLASS_MAGIC = 0xCAFEBABE.toInt()
    private const val JAVA_21_CLASS_VERSION = 65

    private val EXPECTED_FINGERPRINTS = linkedMapOf(
        MAIN_RESOURCE to "99b9bf24d05d6ba6267ec07539933a75250e010a961b43f75584d632fc4e2c25",
        MAPPINGS_RESOURCE to "2e9b61f95ae2cd3b53ed39b6f41f8dcb6153f10fb3bb9539aea27b1bb14ea415",
    )

    private val EXPECTED_PUBLIC_MODEL_METHODS = listOf(
        "getCanonicalRoot",
        "getCompatibility",
        "getModules",
    )

    private val REQUIRED_CLASS_REFERENCES = setOf(
        "com/intellij/openapi/application/ReadAction\$CannotReadException",
        "com/intellij/openapi/progress/ProcessCanceledException",
    )

    private val REQUIRED_MEMBERS = setOf(
        member("com/intellij/openapi/application/ApplicationManager", "getApplication", "()Lcom/intellij/openapi/application/Application;"),
        member(APPLICATION, "isDispatchThread", "()Z"),
        member(READ_ACTION, "computeCancellable", "(Lcom/intellij/openapi/util/ThrowableComputable;)Ljava/lang/Object;"),
        member("com/intellij/openapi/progress/ProgressManager", "checkCanceled", "()V"),
        member("com/intellij/openapi/project/Project", "isDisposed", "()Z"),
        member("com/intellij/openapi/project/Project", "isOpen", "()Z"),
        member("com/intellij/openapi/project/Project", "isInitialized", "()Z"),
        member("com/intellij/openapi/project/DumbService\$Companion", "isDumb", "($PROJECT)Z"),
        member("com/intellij/openapi/project/Project", "getBasePath", "()Ljava/lang/String;"),
        member("com/intellij/openapi/externalSystem/service/project/ProjectDataManager", "getInstance", "()Lcom/intellij/openapi/externalSystem/service/project/ProjectDataManager;"),
        member("com/intellij/openapi/externalSystem/service/project/ProjectDataManager", "getExternalProjectsData", "($PROJECT" + "Lcom/intellij/openapi/externalSystem/model/ProjectSystemId;)Ljava/util/Collection;"),
        member("com/intellij/openapi/externalSystem/model/ExternalProjectInfo", "getExternalProjectPath", "()Ljava/lang/String;"),
        member("com/intellij/openapi/externalSystem/model/ExternalProjectInfo", "getExternalProjectStructure", "()Lcom/intellij/openapi/externalSystem/model/DataNode;"),
        member("com/intellij/openapi/externalSystem/model/ExternalProjectInfo", "getLastSuccessfulImportTimestamp", "()J"),
        member("com/intellij/openapi/externalSystem/model/ExternalProjectInfo", "getLastImportTimestamp", "()J"),
        member("com/intellij/openapi/externalSystem/model/DataNode", "isReady", "()Z"),
        member("com/intellij/openapi/module/ModuleManager\$Companion", "getInstance", "($PROJECT)Lcom/intellij/openapi/module/ModuleManager;"),
        member("com/intellij/openapi/module/ModuleManager", "getModules", "()[Lcom/intellij/openapi/module/Module;"),
        member("com/intellij/openapi/module/Module", "isDisposed", "()Z"),
        member("com/intellij/openapi/module/Module", "getName", "()Ljava/lang/String;"),
        member("com/intellij/openapi/roots/ModuleRootManager", "getInstance", "(Lcom/intellij/openapi/module/Module;)Lcom/intellij/openapi/roots/ModuleRootManager;"),
        member("com/intellij/openapi/roots/ModuleRootManager", "getContentEntries", "()[Lcom/intellij/openapi/roots/ContentEntry;"),
        member("com/intellij/openapi/roots/ContentEntry", "getSourceFolders", "()[Lcom/intellij/openapi/roots/SourceFolder;"),
        member("com/intellij/openapi/roots/SourceFolder", "getFile", "()Lcom/intellij/openapi/vfs/VirtualFile;"),
        member("com/intellij/openapi/roots/SourceFolder", "getRootType", "()Lorg/jetbrains/jps/model/module/JpsModuleSourceRootType;"),
        member("com/intellij/openapi/vfs/VirtualFile", "getPath", "()Ljava/lang/String;"),
        member("com/intellij/openapi/roots/ModuleRootManager", "orderEntries", "()Lcom/intellij/openapi/roots/OrderEnumerator;"),
        member("com/intellij/openapi/roots/OrderEnumerator", "forEach", "(Lcom/intellij/util/Processor;)V"),
        member("com/intellij/openapi/roots/OrderEntry", "getFiles", "(Lcom/intellij/openapi/roots/OrderRootType;)[Lcom/intellij/openapi/vfs/VirtualFile;"),
        member("com/intellij/openapi/vfs/VirtualFile", "getUrl", "()Ljava/lang/String;"),
        member("com/intellij/openapi/externalSystem/util/ExternalSystemApiUtil", "isExternalSystemAwareModule", "(Ljava/lang/String;Lcom/intellij/openapi/module/Module;)Z"),
        member("com/intellij/openapi/externalSystem/util/ExternalSystemApiUtil", "getExternalRootProjectPath", "(Lcom/intellij/openapi/module/Module;)Ljava/lang/String;"),
        member("com/intellij/openapi/externalSystem/util/ExternalSystemApiUtil", "getExternalProjectPath", "(Lcom/intellij/openapi/module/Module;)Ljava/lang/String;"),
        member("com/intellij/openapi/externalSystem/util/ExternalSystemApiUtil", "getExternalProjectId", "(Lcom/intellij/openapi/module/Module;)Ljava/lang/String;"),
        member("com/intellij/openapi/roots/ModuleRootManager", "getSdk", "()Lcom/intellij/openapi/projectRoots/Sdk;"),
        member("com/intellij/openapi/projectRoots/Sdk", "getName", "()Ljava/lang/String;"),
        member("com/intellij/openapi/projectRoots/Sdk", "getSdkType", "()Lcom/intellij/openapi/projectRoots/SdkTypeId;"),
        member("com/intellij/openapi/projectRoots/SdkTypeId", "getName", "()Ljava/lang/String;"),
        member("com/intellij/openapi/projectRoots/Sdk", "getVersionString", "()Ljava/lang/String;"),
    )

    private val BLOCKING_READ_METHODS = setOf("compute", "computeBlocking", "run")
    // IDEA 262 has no root-level processor. The pinned adapter must use the stoppable order-entry
    // processor and only the one per-entry root array needed to observe up to root 513.
    private val WHOLE_CLASSPATH_MATERIALIZERS = setOf(
        member(
            "com/intellij/openapi/roots/OrderEnumerator",
            "classes",
            "()Lcom/intellij/openapi/roots/OrderRootsEnumerator;",
        ),
        member(
            "com/intellij/openapi/roots/OrderRootsEnumerator",
            "getUrls",
            "()[Ljava/lang/String;",
        ),
        member(
            "com/intellij/openapi/roots/OrderRootsEnumerator",
            "getRoots",
            "()[Lcom/intellij/openapi/vfs/VirtualFile;",
        ),
        member(
            "com/intellij/openapi/roots/OrderRootsEnumerator",
            "getRootEntries",
            "()Ljava/util/Collection;",
        ),
    )
    private val SMART_WAIT_METHODS = setOf(
        "waitForSmartMode",
        "runWhenSmart",
        "smartInvokeLater",
        "runReadActionInSmartMode",
        "runReadActionInSmartModeWithWriteActionPriority",
    )
    private val DESTRUCTIVE_EXTERNAL_SYSTEM_VERBS = setOf("refresh", "link", "unlink", "import", "prepare", "repair")
    private val FORBIDDEN_VFS_METHODS = setOf("refresh", "contentsToByteArray", "inputStream", "binaryContent", "iterateChildrenRecursively", "visitChildrenRecursively")
        .map(String::lowercase)
    private val FORBIDDEN_FILES_METHODS = setOf("walk", "find", "read", "lines", "newInputStream", "newByteChannel")
        .map(String::lowercase)
    private val MEMBER_ORDER = compareBy<DetachedModelMemberReference>(
        DetachedModelMemberReference::owner,
        DetachedModelMemberReference::name,
        DetachedModelMemberReference::descriptor,
    )
}

internal class DetachedModelClassContractTest {
    @Test
    fun `compiled live adapter matches exact IDEA 262 contract`() {
        assertEquals(
            emptyList<DetachedModelClassContractFailure>(),
            DetachedModelClassContract.verify(),
        )
    }
}

private sealed interface ClassRead {
    data class Admitted(val view: DetachedModelClassView) : ClassRead
    data object Missing : ClassRead
    data object Rejected : ClassRead
}

private data class DetachedModelClassView(
    val majorVersion: Int,
    val classNames: Set<String>,
    val members: Set<DetachedModelMemberReference>,
    val fingerprint: String,
)

private sealed interface DetachedModelPoolEntry {
    data class Utf8(val value: String) : DetachedModelPoolEntry
    data class ClassName(val nameIndex: Int) : DetachedModelPoolEntry
    data class NameAndType(val nameIndex: Int, val descriptorIndex: Int) : DetachedModelPoolEntry
    data class Field(val ownerIndex: Int, val nameAndTypeIndex: Int) : DetachedModelPoolEntry
    data class Method(val ownerIndex: Int, val nameAndTypeIndex: Int) : DetachedModelPoolEntry
    data object Other : DetachedModelPoolEntry
}
