package io.github.amichne.kast.standalone

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyPersistence
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CallHierarchyStats
import io.github.amichne.kast.api.CallNode
import io.github.amichne.kast.api.CallNodeTruncation
import io.github.amichne.kast.api.CallNodeTruncationReason
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.SCHEMA_VERSION
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.Symbol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path

internal class CallHierarchyTraversal(
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
    private val session: StandaloneAnalysisSession,
    private val telemetry: StandaloneTelemetry,
) {
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    fun build(query: CallHierarchyQuery): CallHierarchyResult {
        val file = session.findKtFile(query.position.filePath)
        val rootTarget = resolveTarget(file, query.position.offset)

        return telemetry.inSpan(
            scope = StandaloneTelemetryScope.CALL_HIERARCHY,
            name = "kast.callHierarchy",
            attributes = mapOf(
                "kast.callHierarchy.direction" to query.direction.name,
                "kast.callHierarchy.depth" to query.depth,
                "kast.callHierarchy.maxTotalCalls" to query.maxTotalCalls,
                "kast.callHierarchy.maxChildrenPerNode" to query.maxChildrenPerNode,
                "kast.callHierarchy.timeoutMillis" to (query.timeoutMillis ?: limits.requestTimeoutMillis),
                "kast.callHierarchy.persistToGitShaCache" to query.persistToGitShaCache,
            ),
        ) { span ->
            val cache = if (query.persistToGitShaCache) resolveCache(query) else null
            if (cache == null) {
                span.setAttribute("kast.callHierarchy.cache.available", false)
            }

            cache?.load()?.let { entry ->
                span.setAttribute("kast.callHierarchy.cache.available", true)
                span.setAttribute("kast.callHierarchy.cache.hit", true)
                span.setAttribute("kast.callHierarchy.totalNodes", entry.stats.totalNodes)
                span.setAttribute("kast.callHierarchy.totalEdges", entry.stats.totalEdges)
                span.setAttribute("kast.callHierarchy.filesVisited", entry.stats.filesVisited)
                return@inSpan CallHierarchyResult(
                    root = entry.root,
                    stats = entry.stats,
                    persistence = cache.persistence(cacheHit = true),
                )
            }

            if (cache != null) {
                span.setAttribute("kast.callHierarchy.cache.available", true)
                span.setAttribute("kast.callHierarchy.cache.hit", false)
            }

            val budget = TraversalBudget(
                maxTotalCalls = query.maxTotalCalls,
                maxChildrenPerNode = query.maxChildrenPerNode,
                timeoutMillis = query.timeoutMillis ?: limits.requestTimeoutMillis,
            )
            val root = buildNode(
                target = rootTarget,
                parentCallSite = null,
                direction = query.direction,
                depthRemaining = query.depth,
                pathKeys = emptySet(),
                budget = budget,
                currentDepth = 0,
            )
            val stats = budget.toStats()
            val persistence = cache?.persist(root = root, stats = stats)

            span.setAttribute("kast.callHierarchy.totalNodes", stats.totalNodes)
            span.setAttribute("kast.callHierarchy.totalEdges", stats.totalEdges)
            span.setAttribute("kast.callHierarchy.truncatedNodes", stats.truncatedNodes)
            span.setAttribute("kast.callHierarchy.filesVisited", stats.filesVisited)
            span.setAttribute("kast.callHierarchy.timeoutReached", stats.timeoutReached)
            span.setAttribute("kast.callHierarchy.maxDepthReached", stats.maxDepthReached)

            CallHierarchyResult(
                root = root,
                stats = stats,
                persistence = persistence,
            )
        }
    }

    private fun buildNode(
        target: PsiElement,
        parentCallSite: Location?,
        direction: CallDirection,
        depthRemaining: Int,
        pathKeys: Set<String>,
        budget: TraversalBudget,
        currentDepth: Int,
    ): CallNode = telemetry.inSpan(
        scope = StandaloneTelemetryScope.CALL_HIERARCHY,
        name = "kast.callHierarchy.buildNode",
        attributes = mapOf(
            "kast.callHierarchy.currentDepth" to currentDepth,
            "kast.callHierarchy.depthRemaining" to depthRemaining,
        ),
        verboseOnly = true,
    ) { span ->
        val symbol = target.toSymbolModel(containingDeclaration = null)
        val nodeKey = target.callHierarchySymbolIdentityKey(symbol)
        budget.recordNode(depth = currentDepth)

        if (depthRemaining == 0) {
            return@inSpan CallNode(
                symbol = symbol,
                callSite = parentCallSite,
                children = emptyList(),
            )
        }

        if (budget.timeoutReached()) {
            val truncation = CallNodeTruncation(
                reason = CallNodeTruncationReason.TIMEOUT,
                details = "Traversal timeout reached before expanding children",
            )
            budget.recordTruncation()
            span.setAttribute("kast.callHierarchy.truncation", truncation.reason.name)
            return@inSpan CallNode(
                symbol = symbol,
                callSite = parentCallSite,
                truncation = truncation,
                children = emptyList(),
            )
        }

        val edges = findCallEdges(
            target = target,
            direction = direction,
            budget = budget,
        )
        val children = mutableListOf<CallNode>()
        var truncation: CallNodeTruncation? = null

        for (edge in edges) {
            if (budget.timeoutReached()) {
                truncation = CallNodeTruncation(
                    reason = CallNodeTruncationReason.TIMEOUT,
                    details = "Traversal timeout reached while expanding children",
                )
                budget.timeoutHit = true
                break
            }
            if (budget.totalEdges >= budget.maxTotalCalls) {
                truncation = CallNodeTruncation(
                    reason = CallNodeTruncationReason.MAX_TOTAL_CALLS,
                    details = "Reached maxTotalCalls=${budget.maxTotalCalls}",
                )
                budget.maxTotalCallsHit = true
                break
            }
            if (children.size >= budget.maxChildrenPerNode) {
                truncation = CallNodeTruncation(
                    reason = CallNodeTruncationReason.MAX_CHILDREN_PER_NODE,
                    details = "Reached maxChildrenPerNode=${budget.maxChildrenPerNode}",
                )
                budget.maxChildrenHit = true
                break
            }

            budget.recordEdge()
            val childKey = edge.target.callHierarchySymbolIdentityKey(edge.symbol)
            val child = if (childKey == nodeKey || childKey in pathKeys) {
                budget.recordNode(depth = currentDepth + 1)
                budget.recordTruncation()
                CallNode(
                    symbol = edge.symbol,
                    callSite = edge.callSite,
                    truncation = CallNodeTruncation(
                        reason = CallNodeTruncationReason.CYCLE,
                        details = "Cycle detected for symbol=$childKey",
                    ),
                    children = emptyList(),
                )
            } else {
                buildNode(
                    target = edge.target,
                    parentCallSite = edge.callSite,
                    direction = direction,
                    depthRemaining = depthRemaining - 1,
                    pathKeys = pathKeys + nodeKey,
                    budget = budget,
                    currentDepth = currentDepth + 1,
                )
            }
            children += child
        }

        if (truncation != null) {
            budget.recordTruncation()
            span.setAttribute("kast.callHierarchy.truncation", truncation.reason.name)
        }

        CallNode(
            symbol = symbol,
            callSite = parentCallSite,
            truncation = truncation,
            children = children,
        )
    }

    private fun findCallEdges(
        target: PsiElement,
        direction: CallDirection,
        budget: TraversalBudget,
    ): List<CallEdge> = telemetry.inSpan(
        scope = StandaloneTelemetryScope.CALL_HIERARCHY,
        name = "kast.callHierarchy.findEdges",
        attributes = mapOf("kast.callHierarchy.direction" to direction.name),
        verboseOnly = true,
    ) { span ->
        val edges = when (direction) {
            CallDirection.INCOMING -> incomingCallEdges(target, budget)
            CallDirection.OUTGOING -> outgoingCallEdges(target, budget)
        }.sortedWith(
            compareBy(
                { it.callSite.filePath },
                { it.callSite.startOffset },
                { it.callSite.endOffset },
                { it.symbol.fqName },
                { it.symbol.kind.name },
            ),
        )
        span.setAttribute("kast.callHierarchy.edgeCount", edges.size)
        edges
    }

    private fun incomingCallEdges(
        target: PsiElement,
        budget: TraversalBudget,
    ): List<CallEdge> {
        val edges = mutableListOf<CallEdge>()

        candidateReferenceFiles(target).forEach { candidateFile ->
            if (budget.timeoutReached()) {
                return edges
            }
            budget.visitFile(candidateFile.lookupPath())
            candidateFile.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (budget.timeoutReached()) {
                            stopWalking()
                            return
                        }
                        element.references.forEach { reference ->
                            val resolved = reference.resolve()
                            if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                                val caller = reference.element.callHierarchyDeclaration() ?: return@forEach
                                val callerSymbol = caller.toSymbolModel(containingDeclaration = null)
                                edges += CallEdge(
                                    target = caller,
                                    symbol = callerSymbol,
                                    callSite = reference.callSiteLocation(),
                                )
                            }
                        }
                        super.visitElement(element)
                    }
                },
            )
        }

        return edges
    }

    private fun outgoingCallEdges(
        target: PsiElement,
        budget: TraversalBudget,
    ): List<CallEdge> {
        val declaration = target.callHierarchyDeclaration() ?: return emptyList()
        budget.visitFile(declaration.lookupPath())
        val edges = mutableListOf<CallEdge>()

        declaration.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (budget.timeoutReached()) {
                        stopWalking()
                        return
                    }
                    if (element !== declaration && element.callHierarchyDeclaration() === element) {
                        return
                    }
                    element.references.forEach { reference ->
                        val resolved = reference.resolve() ?: return@forEach
                        edges += CallEdge(
                            target = resolved,
                            symbol = resolved.toSymbolModel(containingDeclaration = null),
                            callSite = reference.callSiteLocation(),
                        )
                    }
                    super.visitElement(element)
                }
            },
        )

        return edges
    }

    private fun candidateReferenceFiles(target: PsiElement): List<KtFile> {
        val searchIdentifier = target.referenceSearchIdentifier() ?: return session.allKtFiles()
        val anchorFilePath = target.lookupPath()
        val candidatePaths = session.candidateKotlinFilePaths(
            identifier = searchIdentifier,
            anchorFilePath = anchorFilePath,
        )
        if (candidatePaths.isEmpty()) {
            return session.allKtFiles()
        }

        return candidatePaths.map(session::findKtFile)
    }

    private fun resolveCache(query: CallHierarchyQuery): CallHierarchyCache? {
        val gitSha = resolveGitSha() ?: return null
        val cacheDirectory = workspaceRoot.resolve(".kast/call-hierarchy").resolve(gitSha)
        val cacheKey = FileHashing.sha256(
            listOf(
                SCHEMA_VERSION.toString(),
                query.position.filePath,
                query.position.offset.toString(),
                query.direction.name,
                query.depth.toString(),
                query.maxTotalCalls.toString(),
                query.maxChildrenPerNode.toString(),
                query.timeoutMillis?.toString() ?: "null",
            ).joinToString("|"),
        )
        return CallHierarchyCache(
            gitSha = gitSha,
            cacheFilePath = cacheDirectory.resolve("$cacheKey.json"),
            json = json,
        )
    }

    private fun resolveGitSha(): String? = runCatching {
        val process = ProcessBuilder("git", "-C", workspaceRoot.toString(), "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.matches(Regex("^[0-9a-fA-F]{40}$"))) {
            output
        } else {
            null
        }
    }.getOrNull()
}

private data class CallEdge(
    val target: PsiElement,
    val symbol: Symbol,
    val callSite: Location,
)

private class TraversalBudget(
    val maxTotalCalls: Int,
    val maxChildrenPerNode: Int,
    timeoutMillis: Long,
) {
    private val startedAtNanos = System.nanoTime()
    private val timeoutNanos = timeoutMillis * 1_000_000
    private val visitedFiles = linkedSetOf<String>()

    var totalNodes: Int = 0
    var totalEdges: Int = 0
    var truncatedNodes: Int = 0
    var maxDepthReached: Int = 0
    var timeoutHit: Boolean = false
    var maxTotalCallsHit: Boolean = false
    var maxChildrenHit: Boolean = false

    fun visitFile(filePath: String) {
        visitedFiles += filePath
    }

    fun recordNode(depth: Int) {
        totalNodes += 1
        if (depth > maxDepthReached) {
            maxDepthReached = depth
        }
    }

    fun recordEdge() {
        totalEdges += 1
    }

    fun recordTruncation() {
        truncatedNodes += 1
    }

    fun timeoutReached(): Boolean {
        if (timeoutHit) {
            return true
        }
        if (System.nanoTime() - startedAtNanos >= timeoutNanos) {
            timeoutHit = true
        }
        return timeoutHit
    }

    fun toStats(): CallHierarchyStats = CallHierarchyStats(
        totalNodes = totalNodes,
        totalEdges = totalEdges,
        truncatedNodes = truncatedNodes,
        maxDepthReached = maxDepthReached,
        timeoutReached = timeoutHit,
        maxTotalCallsReached = maxTotalCallsHit,
        maxChildrenPerNodeReached = maxChildrenHit,
        filesVisited = visitedFiles.size,
    )
}

private class CallHierarchyCache(
    private val gitSha: String,
    private val cacheFilePath: Path,
    private val json: Json,
) {
    fun load(): CallHierarchyCacheEntry? {
        if (!Files.isRegularFile(cacheFilePath)) {
            return null
        }

        return CallHierarchyCacheEntry.decode(
            json = json,
            raw = Files.readString(cacheFilePath),
        )
    }

    fun persist(
        root: CallNode,
        stats: CallHierarchyStats,
    ): CallHierarchyPersistence {
        val payload = CallHierarchyCacheEntry(
            root = root,
            stats = stats,
            schemaVersion = SCHEMA_VERSION,
        )
        Files.createDirectories(cacheFilePath.parent)
        Files.writeString(cacheFilePath, payload.toJson(json).toString())
        return persistence(cacheHit = false)
    }

    fun persistence(cacheHit: Boolean): CallHierarchyPersistence = CallHierarchyPersistence(
        gitSha = gitSha,
        cacheFilePath = cacheFilePath.toString(),
        cacheHit = cacheHit,
    )
}

private data class CallHierarchyCacheEntry(
    val root: CallNode,
    val stats: CallHierarchyStats,
    val schemaVersion: Int,
) {
    fun toJson(json: Json) = buildJsonObject {
        put("root", json.encodeToJsonElement(CallNode.serializer(), root))
        put("stats", json.encodeToJsonElement(CallHierarchyStats.serializer(), stats))
        put("schemaVersion", schemaVersion)
    }

    companion object {
        fun decode(
            json: Json,
            raw: String,
        ): CallHierarchyCacheEntry {
            val payload = json.parseToJsonElement(raw).jsonObject
            return CallHierarchyCacheEntry(
                root = json.decodeFromJsonElement(
                    CallNode.serializer(),
                    payload.getValue("root"),
                ),
                stats = json.decodeFromJsonElement(
                    CallHierarchyStats.serializer(),
                    payload.getValue("stats"),
                ),
                schemaVersion = payload["schemaVersion"]?.jsonPrimitive?.intOrNull ?: SCHEMA_VERSION,
            )
        }
    }
}

private fun PsiElement.lookupPath(): String = containingFile.virtualFile?.path
                                              ?: containingFile.viewProvider.virtualFile.path

private fun PsiElement.callHierarchySymbolIdentityKey(
    symbol: Symbol,
): String = buildString {
    append(symbol.fqName)
    append('|')
    append(lookupPath())
    append(':')
    append(symbol.location.startOffset)
    append('-')
    append(symbol.location.endOffset)
}

private fun com.intellij.psi.PsiReference.callSiteLocation(): Location {
    val elementStart = element.textRange.startOffset
    return element.toKastLocation(
        TextRange(
            elementStart + rangeInElement.startOffset,
            elementStart + rangeInElement.endOffset,
        ),
    )
}

private fun KtFile.lookupPath(): String = virtualFile?.path ?: viewProvider.virtualFile.path
