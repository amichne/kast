package io.github.amichne.kast.workspace.intellij.read

import java.io.DataInputStream
import java.io.IOException

internal sealed interface EpochClassContractFailure {
    data class ResourceRejected(val resource: String) : EpochClassContractFailure

    data class MissingMember(val member: EpochMemberReference) : EpochClassContractFailure

    data class ForbiddenMember(val member: EpochMemberReference) : EpochClassContractFailure

    data class MissingClassReference(val className: String) : EpochClassContractFailure

    data object WorkspaceListenerDescriptorMissing : EpochClassContractFailure

    data class IdeMemberSetMismatch(
        val resource: String,
        val expected: Set<EpochMemberReference>,
        val observed: Set<EpochMemberReference>,
    ) : EpochClassContractFailure
}

internal data class EpochMemberReference(val owner: String, val name: String)

internal object EpochSignalClassContract {
    fun verify(readBytes: (String) -> ByteArray? = ::readContractResource): List<EpochClassContractFailure> {
        val admitted = RESOURCES.associateWith { resource -> readClassView(resource, readBytes) }
        val rejected = admitted.mapNotNull { (resource, result) ->
            if (result == null) EpochClassContractFailure.ResourceRejected(resource) else null
        }
        if (rejected.isNotEmpty()) return rejected
        val pools = admitted.mapValues { (_, value) -> requireNotNull(value) }
        val combined = pools.getValue(CONTRACT_RESOURCE) + pools.getValue(LISTENER_RESOURCE)
        return buildList {
            REQUIRED_MEMBERS.filterNot(combined.members::contains).forEach { missing ->
                add(EpochClassContractFailure.MissingMember(missing))
            }
            if (combined.utf8.none { WORKSPACE_LISTENER_DESCRIPTOR in it }) {
                add(EpochClassContractFailure.WorkspaceListenerDescriptorMissing)
            }
            pools.values
                .flatMap { it.members }
                .filter(::rejectsProductionMember)
                .distinct()
                .forEach { forbidden ->
                    add(EpochClassContractFailure.ForbiddenMember(forbidden))
                }
            EXPECTED_IDE_MEMBERS.forEach { (resource, expected) ->
                val observed = pools.getValue(resource).members.filterTo(linkedSetOf(), ::isIdeMember)
                if (observed != expected) {
                    add(EpochClassContractFailure.IdeMemberSetMismatch(resource, expected, observed))
                }
            }
        }
    }

    /** Checks required IDE APIs and prohibited effects across the production epoch resource inventory. */
    fun verifyProductionEpoch(
        readBytes: (String) -> ByteArray? = ::readContractResource
    ): List<EpochClassContractFailure> {
        val admitted = PRODUCTION_RESOURCES.associateWith { resource -> readClassView(resource, readBytes) }
        val rejected = admitted.mapNotNull { (resource, result) ->
            if (result == null) EpochClassContractFailure.ResourceRejected(resource) else null
        }
        if (rejected.isNotEmpty()) return rejected
        val pools = admitted.mapValues { (_, value) -> requireNotNull(value) }
        val combined = pools.values.reduce(ConstantPoolView::plus)
        return buildList {
            PRODUCTION_REQUIRED_MEMBERS.filterNot(combined.members::contains).forEach { missing ->
                add(EpochClassContractFailure.MissingMember(missing))
            }
            PRODUCTION_REQUIRED_CLASS_REFERENCES.filterNot { className ->
                    combined.utf8.any { value -> className in value }
                }
                .forEach { missing ->
                    add(EpochClassContractFailure.MissingClassReference(missing))
                }
            combined.members.filter(::rejectsProductionMember).forEach { forbidden ->
                add(EpochClassContractFailure.ForbiddenMember(forbidden))
            }
            PRODUCTION_LISTENER_IDE_MEMBERS.forEach { (resource, expected) ->
                val observed = pools.getValue(resource).members.filterTo(linkedSetOf(), ::isIdeMember)
                if (observed != expected) {
                    add(EpochClassContractFailure.IdeMemberSetMismatch(resource, expected, observed))
                }
            }
        }
    }

    private fun readClassView(resource: String, readBytes: (String) -> ByteArray?): ConstantPoolView? {
        val bytes = readBytes(resource) ?: return null
        return try {
            DataInputStream(bytes.inputStream()).use(::parseConstantPool)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    private fun parseConstantPool(input: DataInputStream): ConstantPoolView? {
        if (input.readInt() != 0xCAFEBABE.toInt()) return null
        input.readUnsignedShort()
        input.readUnsignedShort()
        val entries = arrayOfNulls<ConstantPoolEntry>(input.readUnsignedShort())
        var index = 1
        while (index < entries.size) {
            when (input.readUnsignedByte()) {
                1 -> entries[index] = ConstantPoolEntry.Utf8(input.readUTF())
                3,
                4 -> input.skipBytes(4).also { entries[index] = ConstantPoolEntry.Other }
                5,
                6 -> {
                    input.skipBytes(8)
                    entries[index] = ConstantPoolEntry.Other
                    index += 1
                }
                7 -> entries[index] = ConstantPoolEntry.ClassName(input.readUnsignedShort())
                8,
                16,
                19,
                20 -> input.skipBytes(2).also { entries[index] = ConstantPoolEntry.Other }
                9,
                10,
                11 ->
                    entries[index] =
                        ConstantPoolEntry.Member(
                            input.readUnsignedShort(),
                            input.readUnsignedShort(),
                        )
                12 ->
                    entries[index] =
                        ConstantPoolEntry.NameAndType(
                            input.readUnsignedShort(),
                            input.readUnsignedShort(),
                        )
                17,
                18 -> input.skipBytes(4).also { entries[index] = ConstantPoolEntry.Other }
                15 -> input.skipBytes(3).also { entries[index] = ConstantPoolEntry.Other }
                else -> return null
            }
            index += 1
        }
        fun utf8(at: Int) = (entries[at] as ConstantPoolEntry.Utf8).value
        val members =
            entries
                .filterIsInstance<ConstantPoolEntry.Member>()
                .map { member ->
                    val owner = entries[member.ownerIndex] as ConstantPoolEntry.ClassName
                    val name = entries[member.nameAndTypeIndex] as ConstantPoolEntry.NameAndType
                    EpochMemberReference(utf8(owner.nameIndex), utf8(name.nameIndex))
                }
                .toSet()
        return ConstantPoolView(
            entries.filterIsInstance<ConstantPoolEntry.Utf8>().mapTo(linkedSetOf()) { it.value },
            members,
        )
    }

    private val REQUIRED_MEMBERS =
        setOf(
            member("com/intellij/platform/backend/workspace/WorkspaceModelTopics", "CHANGED"),
            member("com/intellij/openapi/vfs/VirtualFileManager", "VFS_CHANGES"),
            member("com/intellij/util/messages/MessageBusConnection", "subscribe"),
            member(EXTERNAL_PROJECT_INFO, "getLastImportTimestamp"),
            member(EXTERNAL_PROJECT_INFO, "getLastSuccessfulImportTimestamp"),
            member("com/intellij/psi/util/PsiModificationTracker", "getModificationCount"),
            member("com/intellij/openapi/roots/ProjectRootModificationTracker", "getModificationCount"),
            member("com/intellij/openapi/project/DumbService", "getModificationTracker"),
            member("com/intellij/openapi/project/DumbService", "isDumb"),
            member(VFS_EVENT, "getPath"),
            member(VFS_MOVE_EVENT, "getOldPath"),
            member(VFS_MOVE_EVENT, "getNewPath"),
            member(VFS_PROPERTY_EVENT, "isRename"),
            member(VFS_PROPERTY_EVENT, "getOldPath"),
            member(VFS_PROPERTY_EVENT, "getNewPath"),
            member(LOCAL + "EpochVfsMetadataCounter", "recordEvents"),
        )

    private val EXPECTED_IDE_MEMBERS =
        mapOf(
            CONTRACT_RESOURCE to
                setOf(
                    member("com/intellij/openapi/project/Project", "getMessageBus"),
                    member("com/intellij/util/messages/MessageBus", "connect"),
                    member("com/intellij/platform/backend/workspace/WorkspaceModelTopics", "CHANGED"),
                    member("com/intellij/util/messages/MessageBusConnection", "subscribe"),
                    member("com/intellij/openapi/vfs/VirtualFileManager", "VFS_CHANGES"),
                    member("com/intellij/openapi/project/DumbService", "Companion"),
                    member("com/intellij/openapi/project/DumbService\$Companion", "getInstance"),
                    member(EXTERNAL_PROJECT_INFO, "getLastImportTimestamp"),
                    member(EXTERNAL_PROJECT_INFO, "getLastSuccessfulImportTimestamp"),
                    member("com/intellij/psi/util/PsiModificationTracker", "getInstance"),
                    member("com/intellij/psi/util/PsiModificationTracker", "getModificationCount"),
                    member("com/intellij/openapi/roots/ProjectRootModificationTracker", "getInstance"),
                    member("com/intellij/openapi/roots/ProjectRootModificationTracker", "getModificationCount"),
                    member("com/intellij/openapi/project/DumbService", "getModificationTracker"),
                    member("com/intellij/openapi/util/ModificationTracker", "getModificationCount"),
                    member("com/intellij/openapi/project/DumbService", "isDumb"),
                ),
            LISTENER_RESOURCE to
                setOf(
                    member(VFS_MOVE_EVENT, "getOldPath"),
                    member(VFS_MOVE_EVENT, "getNewPath"),
                    member(VFS_PROPERTY_EVENT, "isRename"),
                    member(VFS_PROPERTY_EVENT, "getOldPath"),
                    member(VFS_PROPERTY_EVENT, "getNewPath"),
                    member(VFS_PROPERTY_EVENT, "getPath"),
                    member(VFS_EVENT, "getPath"),
                ),
            COUNTER_RESOURCE to emptySet(),
            ROOT_RESOURCE to emptySet(),
        )

    private fun member(owner: String, name: String) = EpochMemberReference(owner, name)

    private fun isIdeMember(member: EpochMemberReference): Boolean = member.owner.startsWith("com/intellij/")

    internal fun rejectsProductionMember(member: EpochMemberReference): Boolean {
        val name = member.name.lowercase()
        return when {
            member.owner == "com/intellij/openapi/vfs/VirtualFileManager" &&
                (name.contains("refresh") || member.name in CONSTANT_ZERO_VFS_METHODS) -> true
            member.owner == "com/intellij/openapi/vfs/LocalFileSystem" && name.contains("refresh") -> true
            member.owner == "com/intellij/openapi/vfs/VirtualFile" && name == "getchildren" -> true
            member.owner in setOf("com/intellij/openapi/vfs/VfsUtil", "com/intellij/openapi/vfs/VfsUtilCore") &&
                name in setOf("iteratechildrenrecursively", "visitchildrenrecursively", "processfilesrecursively") ->
                true
            member.owner.startsWith("com/intellij/openapi/externalSystem/") &&
                FORBIDDEN_EXTERNAL_SYSTEM_VERBS.any(name::startsWith) -> true
            member.owner == "com/intellij/openapi/application/Application" &&
                member.name in setOf("invokeLater", "runReadAction") -> true
            member.owner == "java/nio/file/Files" && FORBIDDEN_FILE_VERBS.any(name::startsWith) -> true
            member.owner == "java/lang/Thread" && member.name in setOf("start", "sleep") -> true
            member.owner == "java/lang/Object" && member.name == "wait" -> true
            member.owner == "java/util/concurrent/Future" && member.name == "get" -> true
            member.owner == "java/util/concurrent/CompletableFuture" && member.name in setOf("get", "join") -> true
            member.owner.startsWith("java/util/concurrent/Executors") -> true
            member.owner.startsWith("kotlinx/coroutines/") && name == "runblocking" -> true
            else -> false
        }
    }

    private val PRODUCTION_REQUIRED_MEMBERS =
        setOf(
            member("com/intellij/openapi/application/Application", "isDispatchThread"),
            member("com/intellij/openapi/application/Application", "isWriteAccessAllowed"),
            member(
                "io/github/amichne/kast/workspace/contract/ProjectReadEpoch\$Source",
                "observeBeforeWrite\$io_github_amichne_workspace_workspace_contract",
            ),
            member("com/intellij/openapi/application/ReadAction", "computeCancellable"),
            member("com/intellij/openapi/progress/ProgressManager", "checkCanceled"),
            member("com/intellij/openapi/project/Project", "isDisposed"),
            member("com/intellij/openapi/project/Project", "isOpen"),
            member("com/intellij/openapi/project/Project", "isInitialized"),
            member("com/intellij/openapi/project/Project", "getBasePath"),
            member("com/intellij/platform/backend/workspace/WorkspaceModelTopics", "CHANGED"),
            member("com/intellij/openapi/vfs/VirtualFileManager", "VFS_CHANGES"),
            member("com/intellij/util/messages/MessageBusConnection", "subscribe"),
            member(EXTERNAL_PROJECT_INFO, "getExternalProjectPath"),
            member(EXTERNAL_PROJECT_INFO, "getLastImportTimestamp"),
            member(EXTERNAL_PROJECT_INFO, "getLastSuccessfulImportTimestamp"),
            member("com/intellij/psi/util/PsiModificationTracker", "getModificationCount"),
            member("com/intellij/openapi/roots/ProjectRootModificationTracker", "getModificationCount"),
            member("com/intellij/openapi/project/DumbService", "getModificationTracker"),
            member("com/intellij/openapi/project/DumbService", "isDumb"),
            member(VFS_MOVE_EVENT, "getOldPath"),
            member(VFS_MOVE_EVENT, "getNewPath"),
            member(VFS_PROPERTY_EVENT, "getOldPath"),
            member(VFS_PROPERTY_EVENT, "getNewPath"),
            member(LOCAL + "ProjectReadEpochObservationKt", "observeProjectReadEpochVfsBatch"),
            member(LOCAL + "ProjectReadEpochState\$Companion", "admit"),
        )

    private val PRODUCTION_REQUIRED_CLASS_REFERENCES =
        setOf(
            "com/intellij/openapi/application/ReadAction\$CannotReadException",
            "com/intellij/openapi/progress/ProcessCanceledException",
            "com/intellij/platform/backend/workspace/WorkspaceModelChangeListener",
            "com/intellij/openapi/vfs/newvfs/BulkFileListener",
        )

    private val PRODUCTION_LISTENER_IDE_MEMBERS: Map<String, Set<EpochMemberReference>> =
        mapOf(
            PRODUCTION_WORKSPACE_LISTENER_RESOURCE to
                setOf(member("com/intellij/platform/backend/workspace/WorkspaceModelChangeListener", "beforeChanged")),
            PRODUCTION_VFS_LISTENER_RESOURCE to
                setOf(
                    member(VFS_MOVE_EVENT, "getOldPath"),
                    member(VFS_MOVE_EVENT, "getNewPath"),
                    member(VFS_PROPERTY_EVENT, "isRename"),
                    member(VFS_PROPERTY_EVENT, "getOldPath"),
                    member(VFS_PROPERTY_EVENT, "getNewPath"),
                    member(VFS_PROPERTY_EVENT, "getPath"),
                    member(VFS_EVENT, "getPath"),
                ),
        )

    private val CONSTANT_ZERO_VFS_METHODS = setOf("getModificationCount", "getStructureModificationCount")
    private val FORBIDDEN_EXTERNAL_SYSTEM_VERBS = setOf("refresh", "link", "import", "update", "prepare")
    private val FORBIDDEN_FILE_VERBS = setOf("walk", "read", "newinputstream")

    private const val LOCAL = "io/github/amichne/kast/workspace/intellij/read/"
    private const val EXTERNAL_PROJECT_INFO = "com/intellij/openapi/externalSystem/model/ExternalProjectInfo"
    private const val VFS_EVENT = "com/intellij/openapi/vfs/newvfs/events/VFileEvent"
    private const val VFS_MOVE_EVENT = "com/intellij/openapi/vfs/newvfs/events/VFileMoveEvent"
    private const val VFS_PROPERTY_EVENT = "com/intellij/openapi/vfs/newvfs/events/VFilePropertyChangeEvent"
    private const val WORKSPACE_LISTENER_DESCRIPTOR =
        "Lcom/intellij/platform/backend/workspace/WorkspaceModelChangeListener;"
    private const val CONTRACT_RESOURCE = LOCAL + "EpochSignalApiContract.class"
    private const val LISTENER_RESOURCE = LOCAL + "EpochSignalApiContract\$RootFilteredVfsSignal.class"
    private const val COUNTER_RESOURCE = LOCAL + "EpochVfsMetadataCounter.class"
    private const val ROOT_RESOURCE = LOCAL + "EpochFixtureRoot.class"
    private const val RENAME_RESOURCE = LOCAL + "EpochVfsObservedEvent\$Rename.class"
    private const val PRODUCTION_WORKSPACE_LISTENER_RESOURCE =
        LOCAL + "LiveProjectReadEpochSourceFactory\$createOwned\$1.class"
    private const val PRODUCTION_VFS_LISTENER_RESOURCE = LOCAL + "RootFilteredProjectEpochVfsListener.class"
    private val RESOURCES =
        listOf(
            CONTRACT_RESOURCE,
            LISTENER_RESOURCE,
            COUNTER_RESOURCE,
            ROOT_RESOURCE,
            RENAME_RESOURCE,
        )
    private val PRODUCTION_RESOURCES = productionEpochResources()
}

private data class ConstantPoolView(
    val utf8: Set<String>,
    val members: Set<EpochMemberReference>,
) {
    operator fun plus(other: ConstantPoolView) = ConstantPoolView(utf8 + other.utf8, members + other.members)
}

private sealed interface ConstantPoolEntry {
    data class Utf8(val value: String) : ConstantPoolEntry

    data class ClassName(val nameIndex: Int) : ConstantPoolEntry

    data class NameAndType(val nameIndex: Int, val descriptorIndex: Int) : ConstantPoolEntry

    data class Member(val ownerIndex: Int, val nameAndTypeIndex: Int) : ConstantPoolEntry

    data object Other : ConstantPoolEntry
}
