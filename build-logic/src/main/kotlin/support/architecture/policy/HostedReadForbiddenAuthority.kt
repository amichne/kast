package support.architecture

internal enum class HostedReadForbiddenAuthority(
    val requiredEffect: ForbiddenEffect,
    owner: String,
    name: String,
    private val ownerMatch: HostedReadMemberMatch = HostedReadMemberMatch.EXACT,
    private val nameMatch: HostedReadMemberMatch = HostedReadMemberMatch.EXACT,
    private val roles: Set<ModuleRole> = setOf(ModuleRole.IDE_READ_ONLY),
) {
    FILES_WALK(ForbiddenEffect.REPOSITORY_TRAVERSAL, "java/nio/file/Files", "walk"),
    FILES_WALK_FILE_TREE(ForbiddenEffect.REPOSITORY_TRAVERSAL, "java/nio/file/Files", "walkFileTree"),
    FILES_FIND(ForbiddenEffect.REPOSITORY_TRAVERSAL, "java/nio/file/Files", "find"),
    FILES_LIST(ForbiddenEffect.REPOSITORY_TRAVERSAL, "java/nio/file/Files", "list"),
    FILES_DIRECTORY_STREAM(ForbiddenEffect.REPOSITORY_TRAVERSAL, "java/nio/file/Files", "newDirectoryStream"),
    JAVA_FILE_LIST(ForbiddenEffect.REPOSITORY_TRAVERSAL, "java/io/File", "list"),
    JAVA_FILE_LIST_FILES(ForbiddenEffect.REPOSITORY_TRAVERSAL, "java/io/File", "listFiles"),
    KOTLIN_FILE_TREE_WALK(ForbiddenEffect.REPOSITORY_TRAVERSAL, "kotlin/io/FileTreeWalk", "iterator", nameMatch = HostedReadMemberMatch.ANY),
    KOTLIN_FILE_WALK_BOTTOM_UP(ForbiddenEffect.REPOSITORY_TRAVERSAL, "kotlin/io/FilesKt", "walkBottomUp", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_FILE_WALK_TOP_DOWN(ForbiddenEffect.REPOSITORY_TRAVERSAL, "kotlin/io/FilesKt", "walkTopDown", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_PATH_LIST_ENTRIES(ForbiddenEffect.REPOSITORY_TRAVERSAL, "kotlin/io/path/PathsKt", "listDirectoryEntries", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_PATH_VISIT_TREE(ForbiddenEffect.REPOSITORY_TRAVERSAL, "kotlin/io/path/PathsKt", "visitFileTree", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_PATH_WALK(ForbiddenEffect.REPOSITORY_TRAVERSAL, "kotlin/io/path/PathsKt", "walk", ownerMatch = HostedReadMemberMatch.PREFIX),
    VIRTUAL_FILE_CHILDREN(ForbiddenEffect.REPOSITORY_TRAVERSAL, "com/intellij/openapi/vfs/VirtualFile", "getChildren"),
    VFS_UTIL_ITERATE_CHILDREN(ForbiddenEffect.REPOSITORY_TRAVERSAL, "com/intellij/openapi/vfs/VfsUtil", "iterateChildrenRecursively"),
    VFS_UTIL_PROCESS_FILES(ForbiddenEffect.REPOSITORY_TRAVERSAL, "com/intellij/openapi/vfs/VfsUtil", "processFilesRecursively"),
    VFS_UTIL_VISIT_CHILDREN(ForbiddenEffect.REPOSITORY_TRAVERSAL, "com/intellij/openapi/vfs/VfsUtil", "visitChildrenRecursively"),
    VFS_CORE_ITERATE_CHILDREN(ForbiddenEffect.REPOSITORY_TRAVERSAL, "com/intellij/openapi/vfs/VfsUtilCore", "iterateChildrenRecursively"),
    VFS_CORE_PROCESS_FILES(ForbiddenEffect.REPOSITORY_TRAVERSAL, "com/intellij/openapi/vfs/VfsUtilCore", "processFilesRecursively"),
    VFS_CORE_VISIT_CHILDREN(ForbiddenEffect.REPOSITORY_TRAVERSAL, "com/intellij/openapi/vfs/VfsUtilCore", "visitChildrenRecursively"),
    FILES_LINES(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/nio/file/Files", "lines"),
    FILES_NEW_BUFFERED_READER(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/nio/file/Files", "newBufferedReader"),
    FILES_NEW_BYTE_CHANNEL(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/nio/file/Files", "newByteChannel"),
    FILES_NEW_INPUT_STREAM(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/nio/file/Files", "newInputStream"),
    FILES_READ_ALL_BYTES(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/nio/file/Files", "readAllBytes"),
    FILES_READ_ALL_LINES(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/nio/file/Files", "readAllLines"),
    FILES_READ_STRING(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/nio/file/Files", "readString"),
    FILE_INPUT_STREAM_INIT(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/io/FileInputStream", "<init>"),
    FILE_READER_INIT(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/io/FileReader", "<init>"),
    RANDOM_ACCESS_FILE_INIT(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/io/RandomAccessFile", "<init>"),
    FILE_CHANNEL_OPEN(ForbiddenEffect.PHYSICAL_SOURCE_READ, "java/nio/channels/FileChannel", "open"),
    KOTLIN_FILE_INPUT_STREAM(ForbiddenEffect.PHYSICAL_SOURCE_READ, "kotlin/io/FilesKt", "inputStream", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_FILE_READ_BYTES(ForbiddenEffect.PHYSICAL_SOURCE_READ, "kotlin/io/FilesKt", "readBytes", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_FILE_READ_LINES(ForbiddenEffect.PHYSICAL_SOURCE_READ, "kotlin/io/FilesKt", "readLines", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_FILE_READ_TEXT(ForbiddenEffect.PHYSICAL_SOURCE_READ, "kotlin/io/FilesKt", "readText", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_PATH_INPUT_STREAM(ForbiddenEffect.PHYSICAL_SOURCE_READ, "kotlin/io/path/PathsKt", "inputStream", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_PATH_READ_BYTES(ForbiddenEffect.PHYSICAL_SOURCE_READ, "kotlin/io/path/PathsKt", "readBytes", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_PATH_READ_LINES(ForbiddenEffect.PHYSICAL_SOURCE_READ, "kotlin/io/path/PathsKt", "readLines", ownerMatch = HostedReadMemberMatch.PREFIX),
    KOTLIN_PATH_READ_TEXT(ForbiddenEffect.PHYSICAL_SOURCE_READ, "kotlin/io/path/PathsKt", "readText", ownerMatch = HostedReadMemberMatch.PREFIX),
    VIRTUAL_FILE_CONTENT(ForbiddenEffect.PHYSICAL_SOURCE_READ, "com/intellij/openapi/vfs/VirtualFile", "contentsToByteArray"),
    VIRTUAL_FILE_INPUT_STREAM(ForbiddenEffect.PHYSICAL_SOURCE_READ, "com/intellij/openapi/vfs/VirtualFile", "getInputStream"),
    MESSAGE_DIGEST_UPDATE(ForbiddenEffect.SOURCE_CONTENT_HASH, "java/security/MessageDigest", "update", nameMatch = HostedReadMemberMatch.ANY),
    DIGEST_INPUT_STREAM_INIT(ForbiddenEffect.SOURCE_CONTENT_HASH, "java/security/DigestInputStream", "<init>", nameMatch = HostedReadMemberMatch.ANY),
    GUAVA_HASHER(ForbiddenEffect.SOURCE_CONTENT_HASH, "com/google/common/hash/Hasher", "putBytes", nameMatch = HostedReadMemberMatch.ANY),
    GUAVA_HASH_FUNCTION(ForbiddenEffect.SOURCE_CONTENT_HASH, "com/google/common/hash/HashFunction", "hashBytes", nameMatch = HostedReadMemberMatch.ANY),
    GUAVA_HASHING(ForbiddenEffect.SOURCE_CONTENT_HASH, "com/google/common/hash/Hashing", "sha256", nameMatch = HostedReadMemberMatch.ANY),
    VFS_UTIL_MARK_DIRTY(ForbiddenEffect.RECURSIVE_VFS_REFRESH, "com/intellij/openapi/vfs/VfsUtil", "markDirtyAndRefresh", roles = READ_TRANSITION_ROLES),
    LOCAL_FILE_SYSTEM_REFRESH_FIND(ForbiddenEffect.RECURSIVE_VFS_REFRESH, "com/intellij/openapi/vfs/LocalFileSystem", "refreshAndFind", nameMatch = HostedReadMemberMatch.PREFIX),
    LOCAL_FILE_SYSTEM_REFRESH(ForbiddenEffect.RECURSIVE_VFS_REFRESH, "com/intellij/openapi/vfs/LocalFileSystem", "refresh"),
    LOCAL_FILE_SYSTEM_REFRESH_FILES(ForbiddenEffect.RECURSIVE_VFS_REFRESH, "com/intellij/openapi/vfs/LocalFileSystem", "refreshFiles"),
    VIRTUAL_FILE_REFRESH(ForbiddenEffect.RECURSIVE_VFS_REFRESH, "com/intellij/openapi/vfs/VirtualFile", "refresh"),
    VIRTUAL_FILE_MANAGER_ASYNC_REFRESH(ForbiddenEffect.RECURSIVE_VFS_REFRESH, "com/intellij/openapi/vfs/VirtualFileManager", "asyncRefresh"),
    VIRTUAL_FILE_MANAGER_SYNC_REFRESH(ForbiddenEffect.RECURSIVE_VFS_REFRESH, "com/intellij/openapi/vfs/VirtualFileManager", "syncRefresh"),
    REFRESH_QUEUE(ForbiddenEffect.RECURSIVE_VFS_REFRESH, "com/intellij/openapi/vfs/newvfs/RefreshQueue", "refresh"),
    REFRESH_SESSION_LAUNCH(ForbiddenEffect.RECURSIVE_VFS_REFRESH, "com/intellij/openapi/vfs/newvfs/RefreshSession", "launch"),
    FILE_INDEX_REBUILD(ForbiddenEffect.INDEXING_CYCLE, "com/intellij/util/indexing/FileBasedIndex", "requestRebuild"),
    DUMB_QUEUE_TASK(ForbiddenEffect.INDEXING_CYCLE, "com/intellij/openapi/project/DumbService", "queueTask"),
    GRADLE_LINK_PROJECT(ForbiddenEffect.GRADLE_IMPORT, "com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "linkExternalProject", roles = ALL_ROLES),
    GRADLE_REFRESH_PROJECT(ForbiddenEffect.GRADLE_IMPORT, "com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "refreshProject", roles = ALL_ROLES),
    GRADLE_REFRESH_ALL(ForbiddenEffect.GRADLE_IMPORT, "com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "refreshProjects", roles = ALL_ROLES),
    GRADLE_REFRESH_NEW_PROJECT(ForbiddenEffect.GRADLE_IMPORT, "com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "refreshProjectForNewlyOpenedProject", roles = ALL_ROLES),
    EXTERNAL_SYSTEM_IMPORTING(ForbiddenEffect.GRADLE_IMPORT, "com/intellij/openapi/externalSystem/importing/", "importData", ownerMatch = HostedReadMemberMatch.PREFIX, nameMatch = HostedReadMemberMatch.ANY, roles = ALL_ROLES),
    GRADLE_PROJECT_OPEN(ForbiddenEffect.GRADLE_IMPORT, "org/jetbrains/plugins/gradle/service/project/open/", "open", ownerMatch = HostedReadMemberMatch.PREFIX, nameMatch = HostedReadMemberMatch.ANY, roles = ALL_ROLES),
    GRADLE_PROJECT_SETTINGS_INIT(ForbiddenEffect.GRADLE_IMPORT, "org/jetbrains/plugins/gradle/settings/GradleProjectSettings", "<init>", roles = ALL_ROLES),
    GRADLE_PROJECT_SETTINGS_SET(ForbiddenEffect.GRADLE_IMPORT, "org/jetbrains/plugins/gradle/settings/GradleProjectSettings", "set", nameMatch = HostedReadMemberMatch.PREFIX, roles = ALL_ROLES),
    GRADLE_SETTINGS_SET(ForbiddenEffect.GRADLE_IMPORT, "org/jetbrains/plugins/gradle/settings/GradleSettings", "set", nameMatch = HostedReadMemberMatch.PREFIX, roles = ALL_ROLES),
    GRADLE_SYSTEM_SETTINGS_SET(ForbiddenEffect.GRADLE_IMPORT, "org/jetbrains/plugins/gradle/settings/GradleSystemSettings", "set", nameMatch = HostedReadMemberMatch.PREFIX, roles = ALL_ROLES),
    URL_OPEN_CONNECTION(ForbiddenEffect.NETWORK_ACCESS, "java/net/URL", "openConnection"),
    URL_OPEN_STREAM(ForbiddenEffect.NETWORK_ACCESS, "java/net/URL", "openStream"),
    URL_CONNECTION_CONNECT(ForbiddenEffect.NETWORK_ACCESS, "java/net/URLConnection", "connect"),
    URL_CONNECTION_INPUT(ForbiddenEffect.NETWORK_ACCESS, "java/net/URLConnection", "getInputStream"),
    URL_CONNECTION_OUTPUT(ForbiddenEffect.NETWORK_ACCESS, "java/net/URLConnection", "getOutputStream"),
    URL_CONNECTION_RESPONSE(ForbiddenEffect.NETWORK_ACCESS, "java/net/URLConnection", "getResponseCode"),
    HTTP_CONNECT(ForbiddenEffect.NETWORK_ACCESS, "java/net/HttpURLConnection", "connect"),
    HTTP_INPUT(ForbiddenEffect.NETWORK_ACCESS, "java/net/HttpURLConnection", "getInputStream"),
    HTTP_OUTPUT(ForbiddenEffect.NETWORK_ACCESS, "java/net/HttpURLConnection", "getOutputStream"),
    HTTP_RESPONSE_CODE(ForbiddenEffect.NETWORK_ACCESS, "java/net/HttpURLConnection", "getResponseCode"),
    HTTPS_CONNECT(ForbiddenEffect.NETWORK_ACCESS, "javax/net/ssl/HttpsURLConnection", "connect"),
    HTTPS_INPUT(ForbiddenEffect.NETWORK_ACCESS, "javax/net/ssl/HttpsURLConnection", "getInputStream"),
    HTTPS_OUTPUT(ForbiddenEffect.NETWORK_ACCESS, "javax/net/ssl/HttpsURLConnection", "getOutputStream"),
    HTTPS_RESPONSE_CODE(ForbiddenEffect.NETWORK_ACCESS, "javax/net/ssl/HttpsURLConnection", "getResponseCode"),
    INET_ADDRESS_LOOKUP(ForbiddenEffect.NETWORK_ACCESS, "java/net/InetAddress", "getByName"),
    INET_ADDRESS_LOOKUP_ALL(ForbiddenEffect.NETWORK_ACCESS, "java/net/InetAddress", "getAllByName"),
    SOCKET_FACTORY_CREATE(ForbiddenEffect.NETWORK_ACCESS, "javax/net/SocketFactory", "createSocket"),
    SSL_SOCKET_FACTORY_CREATE(ForbiddenEffect.NETWORK_ACCESS, "javax/net/ssl/SSLSocketFactory", "createSocket"),
    HTTP_CLIENT_SEND(ForbiddenEffect.NETWORK_ACCESS, "java/net/http/HttpClient", "send"),
    HTTP_CLIENT_SEND_ASYNC(ForbiddenEffect.NETWORK_ACCESS, "java/net/http/HttpClient", "sendAsync"),
    SOCKET_INIT(ForbiddenEffect.NETWORK_ACCESS, "java/net/Socket", "<init>"),
    SOCKET_CONNECT(ForbiddenEffect.NETWORK_ACCESS, "java/net/Socket", "connect"),
    SOCKET_INPUT(ForbiddenEffect.NETWORK_ACCESS, "java/net/Socket", "getInputStream"),
    SOCKET_OUTPUT(ForbiddenEffect.NETWORK_ACCESS, "java/net/Socket", "getOutputStream"),
    SOCKET_CHANNEL_OPEN(ForbiddenEffect.NETWORK_ACCESS, "java/nio/channels/SocketChannel", "open"),
    SOCKET_CHANNEL_CONNECT(ForbiddenEffect.NETWORK_ACCESS, "java/nio/channels/SocketChannel", "connect"),
    SERVER_SOCKET_INIT(ForbiddenEffect.NETWORK_ACCESS, "java/net/ServerSocket", "<init>"),
    SERVER_SOCKET_ACCEPT(ForbiddenEffect.NETWORK_ACCESS, "java/net/ServerSocket", "accept"),
    DATAGRAM_CONNECT(ForbiddenEffect.NETWORK_ACCESS, "java/net/DatagramSocket", "connect"),
    DATAGRAM_RECEIVE(ForbiddenEffect.NETWORK_ACCESS, "java/net/DatagramSocket", "receive"),
    DATAGRAM_SEND(ForbiddenEffect.NETWORK_ACCESS, "java/net/DatagramSocket", "send"),
    INTELLIJ_HTTP(ForbiddenEffect.NETWORK_ACCESS, "com/intellij/util/io/HttpRequests", "request", ownerMatch = HostedReadMemberMatch.PREFIX),
    THREAD_JOIN(ForbiddenEffect.BLOCKING_WAIT, "java/lang/Thread", "join"),
    THREAD_SLEEP(ForbiddenEffect.BLOCKING_WAIT, "java/lang/Thread", "sleep"),
    OBJECT_WAIT(ForbiddenEffect.BLOCKING_WAIT, "java/lang/Object", "wait"),
    PROCESS_WAIT_FOR(ForbiddenEffect.BLOCKING_WAIT, "java/lang/Process", "waitFor"),
    FUTURE_GET(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/Future", "get"),
    FUTURE_TASK_GET(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/FutureTask", "get"),
    COMPLETABLE_FUTURE_GET(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/CompletableFuture", "get"),
    COMPLETABLE_FUTURE_JOIN(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/CompletableFuture", "join"),
    COUNTDOWN_LATCH_AWAIT(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/CountDownLatch", "await"),
    SEMAPHORE_ACQUIRE(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/Semaphore", "acquire", nameMatch = HostedReadMemberMatch.PREFIX),
    BLOCKING_QUEUE_PUT(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/BlockingQueue", "put"),
    BLOCKING_QUEUE_TAKE(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/BlockingQueue", "take"),
    CYCLIC_BARRIER_AWAIT(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/CyclicBarrier", "await"),
    CONDITION_AWAIT(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/locks/Condition", "await", nameMatch = HostedReadMemberMatch.PREFIX),
    LOCK_SUPPORT_PARK(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/locks/LockSupport", "park", nameMatch = HostedReadMemberMatch.PREFIX),
    FORK_JOIN_GET(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/ForkJoinTask", "get"),
    FORK_JOIN_JOIN(ForbiddenEffect.BLOCKING_WAIT, "java/util/concurrent/ForkJoinTask", "join"),
    RUN_BLOCKING(ForbiddenEffect.BLOCKING_WAIT, "kotlinx/coroutines/BuildersKt", "runBlocking", ownerMatch = HostedReadMemberMatch.PREFIX),
    IDE_INVOKE_AND_WAIT(ForbiddenEffect.BLOCKING_WAIT, "com/intellij/openapi/application/Application", "invokeAndWait"),
    SYNCHRONOUS_NONBLOCKING_READ(ForbiddenEffect.BLOCKING_WAIT, "com/intellij/openapi/application/NonBlockingReadAction", "executeSynchronously"),
    SWING_INVOKE_AND_WAIT(ForbiddenEffect.BLOCKING_WAIT, "javax/swing/SwingUtilities", "invokeAndWait"),
    ;

    val target: JvmMember = JvmMember.of(owner, name, "()V")

    private fun matches(role: ModuleRole, member: JvmMember): Boolean =
        role in roles &&
            ownerMatch.matches(target.owner.internalName, member.owner.internalName) &&
            nameMatch.matches(target.name.value, member.name.value)

    internal companion object {
        /**
         * Proof transition: `(ModuleRole, JvmMember) -> Set<ForbiddenEffect>`.
         *
         * Establishes all hosted-read hosted forbidden effects from the same finite rule authorities
         * exercised by the generated ASM negative proof. The mapping is total and has no expected
         * failure; raw JVM names are interpreted only inside these closed rules.
         */
        fun classify(role: ModuleRole, target: JvmMember): Set<ForbiddenEffect> = entries
            .filter { it.matches(role, target) }
            .mapTo(linkedSetOf(), HostedReadForbiddenAuthority::requiredEffect)
    }
}

private enum class HostedReadMemberMatch {
    EXACT,
    PREFIX,
    ANY;

    fun matches(expected: String, observed: String): Boolean = when (this) {
        EXACT -> observed == expected
        PREFIX -> observed.startsWith(expected)
        ANY -> true
    }
}

private val ALL_ROLES = ModuleRole.entries.toSet()
private val READ_TRANSITION_ROLES = setOf(
    ModuleRole.IDE_READ_ONLY,
    ModuleRole.INTELLIJ_READ_ADAPTER,
)
