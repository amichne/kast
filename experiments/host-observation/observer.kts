import com.google.gson.Gson
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.ide.script.IdeScriptEngineManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.util.indexing.diagnostic.ProjectIndexingActivityHistoryListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import com.intellij.util.indexing.diagnostic.ProjectScanningHistory
import com.intellij.util.indexing.diagnostic.ProjectDumbIndexingHistory
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException

/** Exact carrier definitions; console bootstrap supplies data bindings, never request source. */
object KastHostObservation {
    private const val BUILD = "262.10315.125"
    private const val FACTORY = "org.jetbrains.kotlin.jsr223.Jsr223KotlincScriptEngineFactory"
    private const val MAX_REQUEST = 16 * 1024
    private const val MAX_RECEIPT = 128 * 1024

    enum class Reason { REQUEST_REJECTED, OUTPUT_REJECTED, HOST_UNSUPPORTED, ENGINE_UNAVAILABLE,
        API_UNAVAILABLE, TARGET_UNAVAILABLE, CAPACITY_EXCEEDED, OWNERSHIP_CONFLICT, RETIREMENT_UNCONFIRMED }
    enum class Stage { REQUEST, HOST, ENGINE, API, PROJECTS, OUTPUT, ATTACH }
    sealed interface Result<out T> {
        data class Admitted<T>(val value: T) : Result<T>
        data class Rejected(val reason: Reason, val stage: Stage) : Result<Nothing>
    }
    sealed interface Intent {
        object Preflight : Intent
        data class Attach(val hostPid: Long, val hostStartedAt: Instant, val targetBasePath: Path,
            val targetLocationHash: String, val sessionId: UUID) : Intent
    }
    class Request private constructor(val id: UUID, val digest: String, val output: Path, val intent: Intent) {
        companion object {
            fun parse(raw: String): Result<Request> {
                val baseNames = setOf("type", "version", "requestId", "artifactSha256", "expectedBuild", "outputDirectory")
                val attachNames = setOf("hostPid", "hostStartedAt", "targetBasePath", "targetLocationHash", "sessionId")
                val names = baseNames + attachNames
                return try {
                    val values = linkedMapOf<String, String>()
                    JsonReader(StringReader(raw)).use { reader ->
                        reader.strictness = Strictness.STRICT
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (name !in names || name in values) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                            val token = if (name == "version" || name == "hostPid") JsonToken.NUMBER else JsonToken.STRING
                            if (reader.peek() != token) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                            values[name] = reader.nextString()
                        }
                        reader.endObject()
                        if (reader.peek() != JsonToken.END_DOCUMENT) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                    }
                    val expectedNames = when (values["type"]) { "PREFLIGHT" -> baseNames; "ATTACH" -> names; else -> return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST) }
                    if (values.keys != expectedNames || values["version"] != "1" ||
                        values["expectedBuild"] != BUILD) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                    val id = UUID.fromString(values.getValue("requestId"))
                    val digest = values.getValue("artifactSha256")
                    val output = values.getValue("outputDirectory")
                    if (id.toString() != values["requestId"] || !digest.matches(Regex("[0-9a-f]{64}")) ||
                        !boundedText(output, 1024) || !Path.of(output).isAbsolute) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                    val intent = when (values["type"]) {
                        "PREFLIGHT" -> Intent.Preflight
                        else -> {
                            val pid = values.getValue("hostPid").toLong()
                            val started = Instant.parse(values.getValue("hostStartedAt"))
                            val base = values.getValue("targetBasePath")
                            val hash = values.getValue("targetLocationHash")
                            val session = UUID.fromString(values.getValue("sessionId"))
                            if (pid <= 0 || started.toString() != values["hostStartedAt"] || !boundedText(base, 1024) || !Path.of(base).isAbsolute || !boundedText(hash, 256) ||
                                session.toString() != values["sessionId"]) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                            Intent.Attach(pid, started, Path.of(base), hash, session)
                        }
                    }
                    Result.Admitted(Request(id, digest, Path.of(output), intent))
                } catch (_: IOException) { rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                } catch (_: java.time.format.DateTimeParseException) { rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                } catch (_: IllegalArgumentException) { rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                } catch (_: IllegalStateException) { rejected(Reason.REQUEST_REJECTED, Stage.REQUEST) }
            }
        }
    }
    data class Host(val pid: Long, val startedAt: String, val build: String, val jbr: String, val type: String = "HOST")
    data class Engine(val factory: String, val name: String, val version: String, val pluginVersion: String, val type: String = "ENGINE")
    data class ProjectDescriptor(val locationHash: String, val basePath: String, val contentRoots: List<String>, val type: String = "PROJECT")
    data class Preflight(val requestId: String, val artifactSha256: String, val host: Host, val engine: Engine,
        val projects: List<ProjectDescriptor>, val type: String = "PREFLIGHT", val version: Int = 1,
        val registrations: Int = 0, val capability: String = "PREFLIGHT_ONLY")
    enum class Ownership { NOT_ACQUIRED, RETIREMENT_UNCONFIRMED }
    data class Rejection(val requestId: String, val artifactSha256: String, val reason: Reason, val stage: Stage,
        val type: String = "REJECTED", val version: Int = 1,
        val ownership: Ownership = if (reason == Reason.RETIREMENT_UNCONFIRMED) Ownership.RETIREMENT_UNCONFIRMED else Ownership.NOT_ACQUIRED)

    private fun rejected(reason: Reason, stage: Stage) = Result.Rejected(reason, stage)
    private fun boundedText(value: String, maximum: Int) = value.isNotEmpty() && value.length <= maximum && value.none { it.code < 32 }

    fun preflight(bindings: Map<String, Any?>, hooks: Hooks = Hooks.None): String {
        val requestPath = bindings["kast.observation.request"] as? String
            ?: return "REQUEST_REJECTED"
        val digest = bindings["kast.observation.digest"] as? String
            ?: return "REQUEST_REJECTED"
        val selectedEngine = bindings["kast.observation.engine"] as? IdeScriptEngineManager.EngineInfo
            ?: return "ENGINE_UNAVAILABLE"
        val request = when (val read = readRequest(Path.of(requestPath))) {
            is Result.Admitted -> read.value
            is Result.Rejected -> return read.reason.name
        }
        if (request.digest != digest) return "REQUEST_REJECTED"
        if (!privateDirectory(request.output) || request.output != Path.of(requestPath).parent.toRealPath()) return "OUTPUT_REJECTED"
        if (Files.exists(request.output.resolve("receipt.json"), NOFOLLOW_LINKS)) return "OUTPUT_REJECTED"
        val result = qualify(request, selectedEngine)
        if (request.intent is Intent.Attach && result is Result.Admitted) {
            val attachment = attach(request, request.intent, result.value, hooks)
            return when (attachment) {
                is Result.Admitted -> when (publish(request.output, attachment.value.receipt)) {
                    is Result.Admitted -> "ATTACHED"
                    is Result.Rejected -> { attachment.value.owner.requestStop(StopCause.OUTPUT_FAILURE); "RETIREMENT_UNCONFIRMED" }
                }
                is Result.Rejected -> {
                    publish(request.output, Rejection(request.id.toString(), digest, attachment.reason, attachment.stage))
                    attachment.reason.name
                }
            }
        }
        val receipt = when (result) {
            is Result.Admitted -> result.value
            is Result.Rejected -> Rejection(request.id.toString(), digest, result.reason, result.stage)
        }
        return when (publish(request.output, receipt)) {
            is Result.Admitted -> when (result) { is Result.Admitted -> "PREFLIGHT_ONLY"; is Result.Rejected -> result.reason.name }
            is Result.Rejected -> "OUTPUT_REJECTED"
        }
    }

    fun readRequest(path: Path): Result<Request> = try {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
        else FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            if (channel.size() > MAX_REQUEST) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
            val bytes = ByteBuffer.allocate(MAX_REQUEST + 1)
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
            if (bytes.position() > MAX_REQUEST) rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
            else Request.parse(Charsets.UTF_8.newDecoder().decode(bytes.flip()).toString())
        }
    } catch (_: IOException) { rejected(Reason.REQUEST_REJECTED, Stage.REQUEST) }

    private fun privateDirectory(path: Path): Boolean = try {
        Files.isDirectory(path, NOFOLLOW_LINKS) && path == path.toRealPath() &&
            Files.getOwner(path) == Files.getOwner(Path.of(System.getProperty("user.home"))) &&
            Files.getPosixFilePermissions(path) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    } catch (_: IOException) { false }

    private fun qualify(request: Request, info: IdeScriptEngineManager.EngineInfo): Result<Preflight> {
        // A console invocation must run away from the EDT; this carrier never schedules live work.
        if (ApplicationManager.getApplication().isDispatchThread) return rejected(Reason.API_UNAVAILABLE, Stage.API)
        val build = ApplicationInfo.getInstance().build.asStringWithoutProductCode()
        val process = ProcessHandle.current()
        val started = process.info().startInstant().orElse(null)
            ?: return rejected(Reason.HOST_UNSUPPORTED, Stage.HOST)
        val jbr = System.getProperty("java.runtime.version", "")
        if (build != BUILD || !boundedText(jbr, 128)) return rejected(Reason.HOST_UNSUPPORTED, Stage.HOST)
        if (info.factoryClass != FACTORY || info.plugin.version != "$BUILD-IJ" ||
            !boundedText(info.engineName, 128) || !boundedText(info.engineVersion, 128)) return rejected(Reason.ENGINE_UNAVAILABLE, Stage.ENGINE)
        // Resolve the exact installed API types/topics without registering any listener.
        try {
            BulkFileListenerBackgroundable::class.java
            VirtualFileManager.VFS_CHANGES_BG
            ProjectIndexingActivityHistoryListener::class.java
        } catch (_: LinkageError) { return rejected(Reason.API_UNAVAILABLE, Stage.API) }
        val projects = when (val capture = projectDescriptors()) {
            is Result.Admitted -> capture.value
            is Result.Rejected -> return capture
        }
        for (project in projects) {
            for (root in project.contentRoots + project.basePath) {
                val path = Path.of(root)
                if (request.output.startsWith(path) || path.startsWith(request.output)) return rejected(Reason.OUTPUT_REJECTED, Stage.OUTPUT)
            }
        }
        return Result.Admitted(Preflight(request.id.toString(), request.digest,
            Host(process.pid(), started.toString(), build, jbr),
            Engine(info.factoryClass, info.engineName, info.engineVersion, info.plugin.version), projects))
    }

    private fun projectDescriptors(): Result<List<ProjectDescriptor>> {
        val detached = ReadAction.nonBlocking(Callable<Result<List<ProjectDescriptor>>> {
            val projects = ProjectManager.getInstance().openProjects
            if (projects.size > 64) return@Callable rejected(Reason.CAPACITY_EXCEEDED, Stage.PROJECTS)
            val descriptors = mutableListOf<ProjectDescriptor>()
            for (project in projects) {
                if (project.isDisposed || !project.isOpen || !project.isInitialized) return@Callable rejected(Reason.TARGET_UNAVAILABLE, Stage.PROJECTS)
                val base = project.basePath ?: return@Callable rejected(Reason.TARGET_UNAVAILABLE, Stage.PROJECTS)
                val roots = ProjectRootManager.getInstance(project).contentRoots
                if (roots.size > 256) return@Callable rejected(Reason.CAPACITY_EXCEEDED, Stage.PROJECTS)
                if (!boundedText(base, 1024) || !boundedText(project.locationHash, 256) ||
                    roots.any { !it.isInLocalFileSystem || !boundedText(it.path, 1024) }) return@Callable rejected(Reason.TARGET_UNAVAILABLE, Stage.PROJECTS)
                descriptors += ProjectDescriptor(project.locationHash, base, roots.map { it.path })
            }
            Result.Admitted(descriptors.toList())
        }).executeSynchronously()
        return when (detached) {
            is Result.Rejected -> detached
            is Result.Admitted -> try {
                // Filesystem canonicalization occurs after leaving the read action.
                Result.Admitted(detached.value.map { descriptor ->
                    descriptor.copy(basePath = Path.of(descriptor.basePath).toRealPath().toString(),
                        contentRoots = descriptor.contentRoots.map { Path.of(it).toRealPath().toString() }.distinct().sorted())
                })
            } catch (_: IOException) { rejected(Reason.TARGET_UNAVAILABLE, Stage.PROJECTS) }
        }
    }

    private fun publish(output: Path, receipt: Any): Result<Unit> {
      return try {
        val bytes = (Gson().toJson(receipt) + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RECEIPT) return rejected(Reason.CAPACITY_EXCEEDED, Stage.OUTPUT)
        // A request directory is single-use; even a second preflight cannot replace its proof.
        val stage = output.resolve("receipt.pending")
        FileChannel.open(stage, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        if (Files.exists(output.resolve("receipt.json"), NOFOLLOW_LINKS)) return rejected(Reason.OUTPUT_REJECTED, Stage.OUTPUT)
        Files.move(stage, output.resolve("receipt.json"), ATOMIC_MOVE)
        Result.Admitted(Unit)
    } catch (_: IOException) { rejected(Reason.OUTPUT_REJECTED, Stage.OUTPUT) }
    }
    enum class StopCause { REQUESTED, PROJECT_CLOSED, OUTPUT_FAILURE, CALLBACK_FAILURE, ATTACH_FAILURE }
    sealed interface StopSignal {
        class Active : StopSignal { val type = "ACTIVE" }
        data class Requested(val cause: StopCause, val type: String = "REQUESTED") : StopSignal
    }
    enum class Life { ATTACHING, OBSERVING, STOPPING, DETACHED, RETIREMENT_UNCONFIRMED }
    enum class Loss { INITIAL_GAP, QUEUE_OVERFLOW, PATH_LIMIT, BATCH_LIMIT, JOURNAL_ROTATED, OUTPUT_FAILURE, CONTROL_REJECTED }
    sealed interface ObservationEvent
    data class VfsObservation(val paths: List<String>, val batchSize: Int, val inspected: Int,
        val limitations: List<Loss>, val type: String = "VFS") : ObservationEvent
    data class ObservationRecord(val sessionId: String, val sequence: Long, val event: ObservationEvent,
        val type: String = "OBSERVATION", val version: Int = 1)
    data class Coverage(val reasons: List<Loss>, val dropped: Long, val type: String = "COVERAGE")
    data class SessionStatus(val sessionId: String, val host: Host, val project: ProjectDescriptor,
        val state: Life, val stopSignal: StopSignal, val coverage: Coverage, val lastSequence: Long, val callbackCount: Int,
        val type: String = "SESSION", val version: Int = 1)
    data class AttachmentReceipt(val requestId: String, val artifactSha256: String, val sessionId: String,
        val host: Host, val project: ProjectDescriptor, val sessionDirectory: String,
        val type: String = "ATTACHED", val version: Int = 1)
    enum class ActivityKind { SCAN_STARTED, SCAN_FINISHED, INDEX_STARTED, INDEX_FINISHED }
    enum class Cancellation { UNKNOWN, CANCELLED, NOT_CANCELLED }
    enum class DumbTransition { ENTERED, EXITED }
    data class ActivityObservation(val activity: ActivityKind, val activityId: Long, val cancellation: Cancellation,
        val type: String = "INDEXING") : ObservationEvent
    data class DumbObservation(val transition: DumbTransition, val type: String = "DUMB") : ObservationEvent
    enum class Resource { STORAGE, DISPOSABLE, VFS, INDEXING, DUMB, WRITER, CONTROL }
    interface Hooks {
        fun acquired(resource: Resource) {}
        fun beforeProjection() {}
        fun beforeWrite() {}
        fun retirementStarted() {}
        object None : Hooks
    }
    private data class Attached(val owner: Session, val receipt: AttachmentReceipt)

    /** The only callback admission boundary. An issued permit remains accounted until close. */
    class CallbackGate {
        private var accepting = false
        private var permanentlyClosed = false
        private var callbacks = 0
        sealed interface Admission {
            class Admitted internal constructor(val permit: Permit) : Admission
            object Closed : Admission
            object Saturated : Admission
        }
        class Permit internal constructor(private val owner: CallbackGate) : AutoCloseable {
            private val retired = AtomicBoolean()
            override fun close() { if (retired.compareAndSet(false, true)) owner.leave() }
        }
        @Synchronized fun open() { if (!permanentlyClosed) accepting = true }
        @Synchronized fun enter(): Admission {
            if (!accepting) return Admission.Closed
            if (callbacks == 32) return Admission.Saturated
            callbacks++
            return Admission.Admitted(Permit(this))
        }
        @Synchronized fun revoke() { accepting = false; permanentlyClosed = true }
        @Synchronized private fun leave() { callbacks-- }
        @Synchronized fun active(): Int = callbacks
    }

    private fun digestText(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun namespace(intent: Intent.Attach): Path = Path.of(System.getProperty("user.home"), ".local", "state", "kast-host-observation")
        .resolve(digestText("${intent.hostPid}\n${intent.hostStartedAt}"))
        .resolve(digestText("${intent.targetLocationHash}\n${intent.targetBasePath}"))

    private fun privateDirectories(path: Path) {
        var ancestor = path
        while (!Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = ancestor.parent
        if (ancestor != ancestor.toRealPath()) throw IOException("noncanonical ancestor")
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        if (!privateDirectory(path)) throw IOException("private directory unavailable")
    }

    private fun attach(request: Request, intent: Intent.Attach, preflight: Preflight, hooks: Hooks): Result<Attached> {
        if (intent.hostPid != preflight.host.pid || intent.hostStartedAt != Instant.parse(preflight.host.startedAt))
            return rejected(Reason.HOST_UNSUPPORTED, Stage.HOST)
        val descriptors = preflight.projects.filter { it.locationHash == intent.targetLocationHash && it.basePath == intent.targetBasePath.toString() }
        if (descriptors.size != 1 || descriptors.single().contentRoots.none { Path.of(it).startsWith(intent.targetBasePath) })
            return rejected(Reason.TARGET_UNAVAILABLE, Stage.PROJECTS)
        val descriptor = descriptors.single()
        val project = ReadAction.nonBlocking(Callable {
            ProjectManager.getInstance().openProjects.filter { !it.isDisposed && it.isOpen && it.isInitialized &&
                it.locationHash == intent.targetLocationHash && it.basePath == intent.targetBasePath.toString() }.singleOrNull()
        }).executeSynchronously() ?: return rejected(Reason.TARGET_UNAVAILABLE, Stage.PROJECTS)
        val space = namespace(intent)
        for (root in preflight.projects.flatMap { it.contentRoots + it.basePath }) {
            if (space.startsWith(Path.of(root)) || Path.of(root).startsWith(space)) return rejected(Reason.OUTPUT_REJECTED, Stage.OUTPUT)
        }
        try { privateDirectories(space) } catch (_: IOException) { return rejected(Reason.OUTPUT_REJECTED, Stage.OUTPUT) }
        if (Files.exists(space.resolve("sessions").resolve(intent.sessionId.toString()), NOFOLLOW_LINKS))
            return rejected(Reason.REQUEST_REJECTED, Stage.ATTACH)
        val admission = space.resolve("admission")
        try {
            Files.createDirectory(admission, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } catch (_: java.nio.file.FileAlreadyExistsException) { return rejected(Reason.OWNERSHIP_CONFLICT, Stage.ATTACH)
        } catch (_: IOException) { return rejected(Reason.OUTPUT_REJECTED, Stage.OUTPUT) }
        // From this point only this owner may release admission. Contenders never enter cleanup.
        val session = Session(project, descriptor, preflight.host, intent.sessionId, space, admission, hooks)
        return when (session.start()) {
            is Result.Admitted -> Result.Admitted(Attached(session,
                AttachmentReceipt(request.id.toString(), request.digest, intent.sessionId.toString(), preflight.host, descriptor, session.directory.toString())))
            is Result.Rejected -> {
                session.requestStop(StopCause.ATTACH_FAILURE)
                rejected(Reason.RETIREMENT_UNCONFIRMED, Stage.ATTACH)
            }
        }
    }

    private class Session(private val project: Project, private val descriptor: ProjectDescriptor,
        private val host: Host, private val id: UUID, private val space: Path, private val admission: Path, private val hooks: Hooks) {
        val directory: Path = space.resolve("sessions").resolve(id.toString())
        private val gate = CallbackGate()
        private val queue = ArrayBlockingQueue<ObservationRecord>(256)
        private val state = AtomicReference(Life.ATTACHING)
        private val running = StopSignal.Active()
        private val stop = AtomicReference<StopSignal>(running)
        private val acquired = CountDownLatch(1)
        private val producerDone = CountDownLatch(1)
        private val controlDone = CountDownLatch(1)
        private val retired = CountDownLatch(1)
        private val losses = java.util.EnumSet.of(Loss.INITIAL_GAP)
        private var dropped = 0L
        private var sequence = 0L
        private val startedWriter = AtomicBoolean()
        private val startedControl = AtomicBoolean()
        private val directoryOwned = AtomicBoolean()
        private val writerClosed = AtomicBoolean(true)
        private val owner = Disposable { requestStop(StopCause.PROJECT_CLOSED) }
        private val roots = (descriptor.contentRoots + descriptor.basePath).distinct().map { it.trimEnd('/') }

        private fun thread(suffix: String, body: () -> Unit) = Thread({ body() }, "kast-observation-$id-$suffix").apply {
            isDaemon = true
            contextClassLoader = Project::class.java.classLoader
        }
        @Synchronized private fun lose(reason: Loss) { losses.add(reason); if (dropped < Long.MAX_VALUE) dropped++ }
        @Synchronized private fun coverage() = Coverage(losses.sortedBy { it.ordinal }, dropped)
        @Synchronized private fun enqueue(event: ObservationEvent) {
            if (sequence == Long.MAX_VALUE) { lose(Loss.QUEUE_OVERFLOW); requestStop(StopCause.CALLBACK_FAILURE); return }
            sequence++
            if (!queue.offer(ObservationRecord(id.toString(), sequence, event))) lose(Loss.QUEUE_OVERFLOW)
        }
        @Synchronized private fun lastSequence() = sequence

        fun start(): Result<Unit> {
            try {
                // Repeat under shared admission; a predecessor may have retired after the first check.
                if (Files.exists(directory, NOFOLLOW_LINKS)) return rejected(Reason.REQUEST_REJECTED, Stage.ATTACH)
                privateDirectories(space.resolve("sessions"))
                retainSessions()
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                directoryOwned.set(true)
                writeAtomic(admission.resolve("owner.json"), mapOf("type" to "OWNER", "sessionId" to id.toString()))
                writeStatus(Life.ATTACHING)
                hooks.acquired(Resource.STORAGE)
                if (project.isDisposed || !project.isOpen) return rejected(Reason.TARGET_UNAVAILABLE, Stage.ATTACH)
                Disposer.register(project, owner)
                hooks.acquired(Resource.DISPOSABLE)
                val connection = ApplicationManager.getApplication().messageBus.connect(owner)
                connection.subscribe(VirtualFileManager.VFS_CHANGES_BG, object : BulkFileListenerBackgroundable {
                    override fun after(events: List<VFileEvent>) = observe { projectVfs(events) }
                })
                hooks.acquired(Resource.VFS)
                connection.subscribe(ProjectIndexingActivityHistoryListener.TOPIC, object : ProjectIndexingActivityHistoryListener {
                    override fun onStartedScanning(history: ProjectScanningHistory) {
                        if (history.project === project) observe { enqueue(ActivityObservation(ActivityKind.SCAN_STARTED, history.indexingActivitySessionId, Cancellation.UNKNOWN)) }
                    }
                    override fun onFinishedScanning(history: ProjectScanningHistory) {
                        if (history.project === project) observe { enqueue(ActivityObservation(ActivityKind.SCAN_FINISHED, history.indexingActivitySessionId,
                            if (history.times.isCancelled) Cancellation.CANCELLED else Cancellation.NOT_CANCELLED)) }
                    }
                    override fun onStartedDumbIndexing(history: ProjectDumbIndexingHistory) {
                        if (history.project === project) observe { enqueue(ActivityObservation(ActivityKind.INDEX_STARTED, history.indexingActivitySessionId, Cancellation.UNKNOWN)) }
                    }
                    override fun onFinishedDumbIndexing(history: ProjectDumbIndexingHistory) {
                        if (history.project === project) observe { enqueue(ActivityObservation(ActivityKind.INDEX_FINISHED, history.indexingActivitySessionId,
                            if (history.times.isCancelled) Cancellation.CANCELLED else Cancellation.NOT_CANCELLED)) }
                    }
                })
                hooks.acquired(Resource.INDEXING)
                project.messageBus.connect(owner).subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
                    override fun enteredDumbMode() = observe { enqueue(DumbObservation(DumbTransition.ENTERED)) }
                    override fun exitDumbMode() = observe { enqueue(DumbObservation(DumbTransition.EXITED)) }
                })
                hooks.acquired(Resource.DUMB)
                if (project.isDisposed || !project.isOpen || stop.get() is StopSignal.Requested) return rejected(Reason.TARGET_UNAVAILABLE, Stage.ATTACH)
                val writer = thread("writer") { writeJournal() }
                val controller = thread("control") { pollControl() }
                writer.start(); startedWriter.set(true); hooks.acquired(Resource.WRITER)
                if (stop.get() is StopSignal.Requested) return rejected(Reason.RETIREMENT_UNCONFIRMED, Stage.ATTACH)
                if (!state.compareAndSet(Life.ATTACHING, Life.OBSERVING)) return rejected(Reason.RETIREMENT_UNCONFIRMED, Stage.ATTACH)
                writeStatus(Life.OBSERVING)
                gate.open()
                controller.start(); startedControl.set(true); hooks.acquired(Resource.CONTROL)
                if (stop.get() is StopSignal.Requested) return rejected(Reason.RETIREMENT_UNCONFIRMED, Stage.ATTACH)
                return Result.Admitted(Unit)
            } catch (_: IOException) { return rejected(Reason.OUTPUT_REJECTED, Stage.ATTACH)
            } catch (cancelled: CancellationException) { requestStop(StopCause.ATTACH_FAILURE); throw cancelled
            } catch (_: RuntimeException) { return rejected(Reason.API_UNAVAILABLE, Stage.ATTACH)
            } catch (_: LinkageError) { return rejected(Reason.API_UNAVAILABLE, Stage.ATTACH)
            } finally { acquired.countDown() }
        }

        private fun observe(action: () -> Unit) {
            when (val entry = gate.enter()) {
                CallbackGate.Admission.Closed -> Unit
                CallbackGate.Admission.Saturated -> lose(Loss.QUEUE_OVERFLOW)
                is CallbackGate.Admission.Admitted -> entry.permit.use {
                    try { hooks.beforeProjection(); action() }
                    catch (cancelled: CancellationException) { requestStop(StopCause.CALLBACK_FAILURE); throw cancelled }
                    catch (_: RuntimeException) { requestStop(StopCause.CALLBACK_FAILURE) }
                    catch (_: LinkageError) { requestStop(StopCause.CALLBACK_FAILURE) }
                }
            }
        }

        private fun projectVfs(events: List<VFileEvent>) {
            val paths = linkedSetOf<String>()
            val limitations = java.util.EnumSet.noneOf(Loss::class.java)
            var chars = 0
            val inspected = minOf(events.size, 128)
            if (inspected < events.size) limitations.add(Loss.BATCH_LIMIT)
            for (index in 0 until inspected) {
                val event = events[index]
                val candidates = when (event) {
                    is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                    is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.newPath) else listOf(event.path)
                    else -> listOf(event.path)
                }
                for (path in candidates) {
                    if (roots.none { path == it || path.startsWith("$it/") }) continue
                    if (!boundedText(path, 1024) || paths.size >= 32 || chars + path.length > 8192) {
                        limitations.add(Loss.PATH_LIMIT); continue
                    }
                    if (paths.add(path)) chars += path.length
                }
            }
            if (paths.isNotEmpty() || limitations.isNotEmpty()) {
                synchronized(this) { losses.addAll(limitations) }
                enqueue(VfsObservation(paths.toList(), events.size, inspected, limitations.sortedBy { it.ordinal }))
            }
        }

        fun requestStop(cause: StopCause) {
            gate.revoke()
            if (!stop.compareAndSet(running, StopSignal.Requested(cause))) return
            state.set(Life.STOPPING)
            thread("retirement") { finalizeSession() }.start()
        }

        private fun pollControl() {
            try {
                var ticks = 0
                while (stop.get() is StopSignal.Active) {
                    val path = directory.resolve("stop.json")
                    if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        val bytes = readBounded(path, MAX_REQUEST)
                        when (val parsed = parseStop(String(bytes, Charsets.UTF_8))) {
                            is Result.Admitted -> if (parsed.value == id) requestStop(StopCause.REQUESTED)
                            is Result.Rejected -> synchronized(this) { losses.add(Loss.CONTROL_REJECTED) }
                        }
                    }
                    if (stop.get() is StopSignal.Active && ++ticks % 10 == 0) writeStatus(Life.OBSERVING)
                    if (stop.get() is StopSignal.Active) Thread.sleep(100)
                }
            } catch (_: IOException) { lose(Loss.OUTPUT_FAILURE); requestStop(StopCause.OUTPUT_FAILURE)
            } catch (_: InterruptedException) { requestStop(StopCause.OUTPUT_FAILURE); Thread.currentThread().interrupt()
            } catch (_: RuntimeException) { requestStop(StopCause.OUTPUT_FAILURE)
            } catch (_: LinkageError) { requestStop(StopCause.OUTPUT_FAILURE)
            } finally {
                if (stop.get() is StopSignal.Active) requestStop(StopCause.OUTPUT_FAILURE)
                controlDone.countDown()
            }
        }

        private fun writeJournal() {
            var channel: FileChannel? = null
            try {
                var segment = 0
                var bytesWritten = 0
                var currentChannel = FileChannel.open(directory.resolve("journal-0.jsonl"), CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                channel = currentChannel
                writerClosed.set(false)
                while (stop.get() is StopSignal.Active || gate.active() != 0 || queue.isNotEmpty()) {
                    val record = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    hooks.beforeWrite()
                    val bytes = (Gson().toJson(record) + "\n").toByteArray(Charsets.UTF_8)
                    if (bytes.size > 64 * 1024) { lose(Loss.PATH_LIMIT); continue }
                    if (bytesWritten + bytes.size > 1024 * 1024) {
                        currentChannel.close()
                        segment = (segment + 1) % 4
                        val path = directory.resolve("journal-$segment.jsonl")
                        if (Files.exists(path, NOFOLLOW_LINKS)) { Files.delete(path); synchronized(this) { losses.add(Loss.JOURNAL_ROTATED) } }
                        currentChannel = FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                        channel = currentChannel
                        bytesWritten = 0
                    }
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) currentChannel.write(buffer)
                    bytesWritten += bytes.size
                }
                currentChannel.force(true)
            } catch (_: IOException) { lose(Loss.OUTPUT_FAILURE); requestStop(StopCause.OUTPUT_FAILURE)
            } catch (_: InterruptedException) { requestStop(StopCause.OUTPUT_FAILURE); Thread.currentThread().interrupt()
            } catch (_: RuntimeException) { lose(Loss.OUTPUT_FAILURE); requestStop(StopCause.OUTPUT_FAILURE)
            } catch (_: LinkageError) { lose(Loss.OUTPUT_FAILURE); requestStop(StopCause.OUTPUT_FAILURE)
            } finally {
                try { channel?.close(); writerClosed.set(true) } catch (_: IOException) { lose(Loss.OUTPUT_FAILURE) }
                if (stop.get() is StopSignal.Active) requestStop(StopCause.OUTPUT_FAILURE)
                producerDone.countDown()
            }
        }

        private fun finalizeSession() {
            try {
                acquired.await()
                hooks.retirementStarted()
                Disposer.dispose(owner)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (gate.active() != 0 && System.nanoTime() < deadline) Thread.sleep(1)
                fun await(latch: CountDownLatch, started: Boolean): Boolean = !started ||
                    latch.await(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                val completed = gate.active() == 0 && await(producerDone, startedWriter.get()) && await(controlDone, startedControl.get())
                if (!completed) {
                    state.set(Life.RETIREMENT_UNCONFIRMED)
                    writeStatus(Life.RETIREMENT_UNCONFIRMED)
                    while (gate.active() != 0) Thread.sleep(10)
                    if (startedWriter.get()) producerDone.await()
                    if (startedControl.get()) controlDone.await()
                }
                // A failed writer may leave admitted records queued. Discard explicitly after producers retire.
                if (!writerClosed.get()) {
                    state.set(Life.RETIREMENT_UNCONFIRMED)
                    writeStatus(Life.RETIREMENT_UNCONFIRMED)
                    return
                }
                while (queue.poll() != null) lose(Loss.OUTPUT_FAILURE)
                // All producers and the only journal writer have retired before terminal publication.
                state.set(Life.DETACHED)
                if (directoryOwned.get()) {
                    writeStatus(Life.DETACHED)
                    writeAtomic(directory.resolve("retired.json"), mapOf("type" to "RETIRED", "version" to 1, "sessionId" to id.toString()))
                }
                Files.deleteIfExists(admission.resolve("owner.json"))
                Files.delete(admission)
                retired.countDown()
            } catch (_: InterruptedException) { state.set(Life.RETIREMENT_UNCONFIRMED); Thread.currentThread().interrupt()
            } catch (_: IOException) { state.set(Life.RETIREMENT_UNCONFIRMED)
            } catch (_: RuntimeException) { state.set(Life.RETIREMENT_UNCONFIRMED) }
        }

        private fun writeStatus(life: Life) = writeAtomic(directory.resolve("status.json"),
            SessionStatus(id.toString(), host, descriptor, life, stop.get(), coverage(), lastSequence(), gate.active()))

        private fun retainSessions() {
            val sessions = space.resolve("sessions")
            val existing = Files.list(sessions).use { it.limit(5).toList() }
            if (existing.size > 4) throw IOException("retention capacity")
            if (existing.size < 4) return
            val eligible = existing.filter { old ->
                if (!Files.isDirectory(old, NOFOLLOW_LINKS) || !old.fileName.toString().matches(Regex("[0-9a-f-]{36}"))) false
                else try {
                    val proof = parseRetired(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readBounded(old.resolve("retired.json"), MAX_REQUEST))).toString())
                    proof is Result.Admitted && proof.value.toString() == old.fileName.toString()
                } catch (_: IOException) { false } catch (_: RuntimeException) { false }
            }.sortedBy { it.fileName.toString() }
            if (eligible.isEmpty()) throw IOException("no proven retired session")
            val old = eligible.first()
            val files = Files.list(old).use { it.limit(8).toList() }
            val allowed = setOf("status.json", "stop.json", "retired.json", "journal-0.jsonl", "journal-1.jsonl", "journal-2.jsonl", "journal-3.jsonl")
            if (files.any { it.fileName.toString() !in allowed || !Files.isRegularFile(it, NOFOLLOW_LINKS) }) throw IOException("retention rejected")
            files.forEach { Files.delete(it) }
            Files.delete(old)
        }
    }

    fun parseStop(raw: String): Result<UUID> = parseSignal(raw, "STOP")
    fun parseRetired(raw: String): Result<UUID> = parseSignal(raw, "RETIRED")
    private fun parseSignal(raw: String, kind: String): Result<UUID> {
        if (raw.length > MAX_REQUEST) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
        return try {
            val values = linkedMapOf<String, String>()
            JsonReader(StringReader(raw)).use { reader ->
                reader.strictness = Strictness.STRICT
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name !in setOf("type", "version", "sessionId") || name in values) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                    if (reader.peek() != if (name == "version") JsonToken.NUMBER else JsonToken.STRING) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
                    values[name] = reader.nextString()
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
            }
            if (values.keys != setOf("type", "version", "sessionId") || values["type"] != kind || values["version"] != "1") return rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
            val id = UUID.fromString(values.getValue("sessionId"))
            if (id.toString() != values["sessionId"]) rejected(Reason.REQUEST_REJECTED, Stage.REQUEST) else Result.Admitted(id)
        } catch (_: IOException) { rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
        } catch (_: IllegalArgumentException) { rejected(Reason.REQUEST_REJECTED, Stage.REQUEST)
        } catch (_: IllegalStateException) { rejected(Reason.REQUEST_REJECTED, Stage.REQUEST) }
    }

    private fun readBounded(path: Path, maximum: Int): ByteArray = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
        if (channel.size() > maximum) throw IOException("input limit")
        val buffer = ByteBuffer.allocate(maximum + 1)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
        if (buffer.position() > maximum) throw IOException("input limit")
        buffer.array().copyOf(buffer.position())
    }
    private fun writeAtomic(path: Path, payload: Any) {
        val bytes = (Gson().toJson(payload) + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RECEIPT) throw IOException("output limit")
        val pending = path.resolveSibling(path.fileName.toString() + ".pending")
        FileChannel.open(pending, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        Files.move(pending, path, ATOMIC_MOVE, REPLACE_EXISTING)
    }

}
