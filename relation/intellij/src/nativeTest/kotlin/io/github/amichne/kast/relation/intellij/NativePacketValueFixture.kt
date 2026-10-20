@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Exact packet sources use a private indexed project and the pinned distribution's standard library. */
abstract class NativePacketValueFixtureTest : HeavyPlatformTestCase() {
    protected abstract val fixtureSdkName: String

    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    protected fun packetFixture(
        paths: List<String>,
        sourceKinds: SymbolSourceKindPolicy = SymbolSourceKindPolicy.PRODUCTION_ONLY,
    ): NativePacketValueFixture {
        lateinit var source: VirtualFile
        var testSource: VirtualFile? = null
        val files = linkedMapOf<String, VirtualFile>()
        val base = getOrCreateProjectBaseDir()
        ApplicationManager.getApplication().invokeAndWait {
            source =
                WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                    base.createChildDirectory(this, "src")
                }
            if (paths.any { it.startsWith("src/test/kotlin/") })
                testSource =
                    WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                        base.createChildDirectory(this, "test")
                    }
            preparePacketModule(source, testSource)
            WriteCommandAction.runWriteCommandAction(project) {
                copyPacketFiles(source, testSource, paths).forEach { (path, file) -> files[path] = file }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).nativeRefined(),
                EvidenceGeneration.parse(1L).nativeRefined(),
            )
        val model = packetModel(lease = lease, base = base, source = source, testSource = testSource)
        val fixture =
            NativePacketValueFixture(
                project = project,
                files = files,
                lease = lease,
                model = model,
                sourceKinds = sourceKinds,
            )
        fixture.assertNoFixtureErrors()
        return fixture
    }

    private fun packetModel(
        lease: SemanticReadLease,
        base: VirtualFile,
        source: VirtualFile,
        testSource: VirtualFile?,
    ) =
        WorkspaceSearchScopeModel.compile(
            lease.workspaceRoot,
            ImportedWorkspaceModelState.COMPLETE,
            listOfNotNull(
                packetRoot(
                    base = base,
                    source = source,
                    sourceSet = "main",
                    kind = WorkspaceSourceRootKind.PRODUCTION,
                ),
                testSource?.let {
                    packetRoot(base = base, source = it, sourceSet = "test", kind = WorkspaceSourceRootKind.TEST)
                },
            ),
        )

    private fun packetRoot(
        base: VirtualFile,
        source: VirtualFile,
        sourceSet: String,
        kind: WorkspaceSourceRootKind,
    ) =
        WorkspaceSourceRootBoundary(
            ideaModuleName = module.name,
            linkedBuildRoot = base.toNioPath(),
            gradleProjectPath = ":packet",
            sourceSetName = sourceSet,
            sourceRoot = source.toNioPath(),
            sourceKind = kind,
            provenance = WorkspaceSourceRootProvenance.AUTHORED,
        )

    private fun copyPacketFiles(
        source: VirtualFile,
        testSource: VirtualFile?,
        paths: List<String>,
    ): Map<String, VirtualFile> = paths.associateWith { path ->
        val isTest = path.startsWith("src/test/kotlin/")
        val relative = path.removePrefix(if (isTest) "src/test/kotlin/" else "src/main/kotlin/")
        var directory = if (isTest) checkNotNull(testSource) else source
        relative.substringBeforeLast('/').split('/').forEach { part ->
            directory = directory.findChild(part) ?: directory.createChildDirectory(this, part)
        }
        directory.createChildData(this, relative.substringAfterLast('/')).also {
            it.setBinaryContent(frozenPacketSource(path))
        }
    }

    private fun preparePacketModule(source: VirtualFile, testSource: VirtualFile?) {
        val sdk = JavaSdk.getInstance().createJdk(fixtureSdkName, System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.sdk = sdk
            model.addContentEntry(source).addSourceFolder(source, false)
            testSource?.let { model.addContentEntry(it).addSourceFolder(it, true) }
            val library = model.moduleLibraryTable.createLibrary("packet-stdlib")
            val roots = library.modifiableModel
            val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
            roots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
            roots.commit()
        }
    }
}

data class PacketDeclaration(val path: String, val owner: String, val name: String)

enum class PacketSeedSyntax {
    QUALIFIED,
    SELECTOR_ONLY,
    CALLEE_ONLY,
}

enum class PacketHostKind {
    FUNCTION,
    CLASS,
}

data class PacketExpression(val enclosing: SymbolSelector, val anchor: ExactDeclarationTextRange)

class NativePacketValueFixture
internal constructor(
    private val project: com.intellij.openapi.project.Project,
    private val files: Map<String, VirtualFile>,
    val lease: SemanticReadLease,
    val model: WorkspaceSearchScopeModelCompilation,
    sourceKinds: SymbolSourceKindPolicy,
) {
    val budget =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(16).nativeRefined(),
                WorkUnitLimit.parse(100_000L).nativeRefined(),
                ElapsedTimeLimitMillis.parse(60_000L).nativeRefined(),
            ),
            RelationByteLimit.parse(1_000_000L).nativeRefined(),
        )
    private val scope =
        SymbolSearchScope.Workspace(
            sourceKinds,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )

    fun assertNoFixtureErrors() = nativeRead {
        files.forEach { (path, virtual) ->
            val file = com.intellij.psi.PsiManager.getInstance(project).findFile(virtual) as KtFile
            val errors =
                IntellijReadObservation.None.observedAnalyze(file) {
                    file
                        .collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                        .filter { it.severity == KaSeverity.ERROR }
                        .map { it.factoryName }
                }
            check(errors.isEmpty()) { "Fixture prerequisite failed for $path: $errors" }
        }
    }

    fun selection(
        owner: PacketDeclaration,
        callee: String,
        callable: PacketDeclaration,
        syntax: PacketSeedSyntax = PacketSeedSyntax.QUALIFIED,
        hostKind: PacketHostKind = PacketHostKind.FUNCTION,
    ): ValueProducerSeedRequest = nativeRead {
        val host = function(owner.path, owner.owner, owner.name)
        val call = invocation(host, callee)
        val expression = call.valueInvocationExpression() as KtExpression
        val selected = declaredCallable(callable)
        val enclosing =
            selector(
                when (hostKind) {
                    PacketHostKind.FUNCTION -> host
                    PacketHostKind.CLASS -> ownerDeclaration(owner.path, owner.owner)
                }
            )
        ValueProducerSeedRequest.create(
                enclosing = enclosing,
                anchor =
                    range(
                        when (syntax) {
                            PacketSeedSyntax.QUALIFIED -> expression
                            PacketSeedSyntax.SELECTOR_ONLY -> call
                            PacketSeedSyntax.CALLEE_ONLY -> checkNotNull(call.calleeExpression)
                        }
                    ),
                expectedCallable = selected,
                budget = budget,
                boundary = RelationSearchBoundary.RETAINED_SUBJECT,
            )
            .nativeRefined()
    }

    fun argumentExpression(owner: PacketDeclaration, callee: String, expression: String): PacketExpression =
        nativeRead {
            val host = function(owner.path, owner.owner, owner.name)
            val call =
                PsiTreeUtil.collectElementsOfType(host, KtCallExpression::class.java).single { candidate ->
                    candidate.calleeExpression?.text == callee &&
                        candidate.valueArguments.any { it.getArgumentExpression()?.text == expression }
                }
            val argument =
                call.valueArguments.mapNotNull { it.getArgumentExpression() }.single { it.text == expression }
            PacketExpression(selector(host), range(argument))
        }

    fun formalParameter(owner: PacketDeclaration, position: Int): PacketExpression = nativeRead {
        val host = function(owner.path, owner.owner, owner.name)
        PacketExpression(selector(host), range(host.valueParameters[position]))
    }

    fun parameterNames(callable: PacketDeclaration): List<String> = nativeRead {
        if (callable.owner.isEmpty())
            return@nativeRead function(callable.path, callable.owner, callable.name).valueParameters.map {
                checkNotNull(it.name)
            }
        val parameters =
            if (callable.name == "<init>") classDeclaration(callable.path, callable.owner).primaryConstructorParameters
            else
                ownerDeclaration(callable.path, callable.owner)
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single { it.name == callable.name }
                    .valueParameters
        parameters.map { checkNotNull(it.name) }
    }

    fun callableDeclaration(callable: PacketDeclaration): SymbolSelector = nativeRead { declaredCallable(callable) }

    private fun invocation(host: KtNamedFunction, callee: String): KtCallExpression {
        val call =
            PsiTreeUtil.collectElementsOfType(host, KtCallExpression::class.java).single {
                it.calleeExpression?.text == callee
            }
        val expression = call.valueInvocationExpression() as KtExpression
        IntellijReadObservation.None.observedAnalyze(call) {
            val resolved = checkNotNull(call.resolveCall()) { "Fixture prerequisite: unresolved $callee invocation" }
            val type = expression.expressionType
            check(type != null && type !is KaErrorType) { "Fixture prerequisite: unresolved $callee result type" }
            check(
                resolved.signature.symbol is KaConstructorSymbol || resolved.signature.symbol is KaNamedFunctionSymbol
            )
        }
        return call
    }

    private fun declaredCallable(callable: PacketDeclaration): SymbolSelector {
        if (callable.owner.isEmpty()) return selector(function(callable.path, callable.owner, callable.name))
        val declaration: PsiNamedElement =
            if (callable.name == "<init>")
                checkNotNull(classDeclaration(callable.path, callable.owner).primaryConstructor)
            else
                ownerDeclaration(callable.path, callable.owner)
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single { it.name == callable.name }
        val selected = selector(declaration)
        check(
            selected.kind ==
                if (callable.name == "<init>") CompilerSymbolKind.CONSTRUCTOR else CompilerSymbolKind.FUNCTION
        ) {
            "Fixture prerequisite: wrong callable kind ${selected.kind}"
        }
        return selected
    }

    private fun function(file: String, owner: String, name: String): KtNamedFunction {
        val declarations =
            if (owner.isEmpty()) {
                val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(checkNotNull(files[file])) as KtFile
                psi.declarations
            } else ownerDeclaration(file, owner).declarations
        return declarations.filterIsInstance<KtNamedFunction>().single { it.name == name }
    }

    private fun classDeclaration(file: String, name: String): KtClass {
        val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(checkNotNull(files[file])) as KtFile
        return PsiTreeUtil.collectElementsOfType(psi, KtClass::class.java).single { it.name == name }
    }

    private fun ownerDeclaration(file: String, name: String): KtClassOrObject {
        val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(checkNotNull(files[file])) as KtFile
        return PsiTreeUtil.collectElementsOfType(psi, KtClassOrObject::class.java).single { it.name == name }
    }

    private fun selector(declaration: PsiNamedElement): SymbolSelector {
        val result = IntellijK2RelationProjection(project, lease.workspaceRoot).project(declaration)
        check(result is IntellijRelationDeclarationProjection.Projected) {
            "Declaration projection failed for ${declaration.javaClass.simpleName}:${declaration.name}: $result"
        }
        return SymbolSelector.issue(lease, scope, result.evidence)
    }
}

private fun frozenPacketSource(path: String): ByteArray {
    val root = Path.of("testData/request-response")
    val expected =
        Files.readAllLines(root.resolve("frozen-sources.sha256")).single { it.endsWith("  $path") }.substringBefore(' ')
    val bytes = Files.readAllBytes(root.resolve(path))
    val actual = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    check(expected == actual) { "Frozen packet source changed: $path" }
    return bytes
}

internal fun range(element: com.intellij.psi.PsiElement): ExactDeclarationTextRange =
    ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset).nativeRefined()

private fun <Value> nativeRead(action: () -> Value): Value = ReadAction.compute<Value, RuntimeException>(action)
