package io.github.amichne.kast.relation.intellij

import com.intellij.codeInsight.ExternalAnnotationsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTopLevelFunctionFqnNameIndex
import org.jetbrains.kotlin.psi.KtClassOrObject

/** A binary declaration can be a subject. Its destinations stay in source, and its body stays unread. */
class NativeLibrarySubjectTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "library-subject-jdk"

    override fun runInDispatchThread() = false

    override fun setUpProject() {
        super.setUpProject()
        // The class-file mirror can initialize this application-lifetime default-project service.
        ExternalAnnotationsManager.getInstance(ProjectManager.getInstance().defaultProject)
    }

    fun testReferencesFindEverySourceUseOfOneOverload() {
        val fixture = libraryFixture()
        val single =
            fixture.complete(RelationMeaning.References) { function("kotlin.collections.listOfNotNull", false) }
        assertEquals(SINGLE_USES, single.sites("listOfNotNull"))
        val vararg = fixture.complete(RelationMeaning.References) { function("kotlin.collections.listOfNotNull", true) }
        assertEquals(VARARG_USES, vararg.sites("listOfNotNull"))
    }

    fun testReferencesFollowAnImportAliasAndAnInfixName() {
        val fixture = libraryFixture()
        val importName = SOURCE.indexOf("filterNotNull as")
        val alias = fixture.complete(RelationMeaning.References) { extension(FILTER, "Iterable") }
        assertEquals(listOf(importName, SOURCE.indexOf("dropNulls()")), alias.offsets())
        // The import names every overload. The aliased call still resolves to exactly one of them.
        val array = fixture.complete(RelationMeaning.References) { extension(FILTER, "Array") }
        assertEquals(listOf(importName), array.offsets())
        val callers = fixture.complete(RelationMeaning.Callers) { extension(FILTER, "Iterable") }
        assertEquals(listOf("useAlias"), callers.facts.map { it.source.name.value })
        val infix = fixture.complete(RelationMeaning.Callers) { function("kotlin.to", false) }
        assertEquals(listOf("usePair"), infix.facts.map { it.source.name.value })
    }

    fun testAnUnusedLibraryFunctionHasNoSourceUse() {
        val fixture = libraryFixture()
        val batch = fixture.complete(RelationMeaning.References) { function("kotlin.collections.emptyList", false) }
        assertEquals(emptyList<Int>(), batch.offsets())
    }

    fun testALibraryClassFindsSourceInheritors() {
        val fixture = libraryFixture()
        val batch = fixture.complete(RelationMeaning.Inheritors) { classlike("kotlin.Lazy") }
        assertEquals(listOf("SourceLazy"), batch.facts.map { it.source.name.value })
    }

    /** Native search misses convention uses of compiled declarations, such as destructuring a library data class. */
    fun testConventionUsesOfABinarySubjectFailClosed() {
        val fixture = libraryFixture()
        val subjects =
            mapOf<String, () -> PsiNamedElement>(
                "operator" to { extension("kotlin.collections.component1", "List") },
                "data property" to
                    {
                        classlike("kotlin.Pair")
                            .let { it as KtClassOrObject }
                            .declarations
                            .single { it.name == "first" } as PsiNamedElement
                    },
            )
        for ((label, subject) in subjects) {
            val read = fixture.read(RelationMeaning.References, subject)
            assertTrue("$label must fail closed, received $read", read is RelationCompilation.Qualified)
        }
    }

    /** A compiled Java member reachable only by its own name is a subject like a compiled Kotlin one. */
    fun testReferencesAndCallersOfACompiledJavaMethod() {
        val fixture = libraryFixture()
        val references = fixture.complete(RelationMeaning.References) { javaMember("java.io.File", "mkdirs") }
        assertEquals(setOf("java method"), references.sites("mkdirs"))
        val callers = fixture.complete(RelationMeaning.Callers) { javaMember("java.io.File", "mkdirs") }
        assertEquals(listOf("useJavaMethod"), callers.facts.map { it.source.name.value })
    }

    fun testReferencesOfACompiledJavaField() {
        val fixture = libraryFixture()
        val references = fixture.complete(RelationMeaning.References) { javaMember("java.io.File", "separatorChar") }
        assertEquals(setOf("java field"), references.sites("separatorChar"))
    }

    /** No Kotlin code contains `parentFile`, so the explicit calls are every use of the getter. */
    fun testACompiledJavaAccessorWithoutPropertyUsesCompletes() {
        val fixture = libraryFixture()
        val references = fixture.complete(RelationMeaning.References) { javaMember("java.io.File", "getParentFile") }
        assertEquals(setOf("java getter"), references.sites("getParentFile"))
    }

    /**
     * Kotlin source reads `getName` as the property `name`, and native search enumerates the method name only. A
     * compiled convention method also answers an operator, which native search misses for compiled declarations.
     */
    fun testCompiledJavaAccessorAndConventionUsesFailClosed() {
        val fixture = libraryFixture()
        for (name in listOf("getName", "compareTo")) {
            val read = fixture.read(RelationMeaning.References) { javaMember("java.io.File", name) }
            assertTrue("$name must fail closed, received $read", read is RelationCompilation.Qualified)
        }
    }

    /** A decompiled body has no calls to read, so an empty callee set would be a false proof. */
    fun testCalleesOfABinaryBodyFailClosed() {
        val read =
            libraryFixture().read(RelationMeaning.Callees) { function("kotlin.collections.listOfNotNull", false) }
        assertFalse("A binary body must not complete, received $read", read is RelationCompilation.Complete)
    }

    private fun RelationBatch.offsets(): List<Int> =
        referenceOccurrences.map { it.occurrence.range.startInclusive }.sorted()

    /** Marked sites keep the expectation independent of offsets computed by the product. */
    private fun RelationBatch.sites(name: String): Set<String> =
        offsets()
            .map { offset ->
                assertEquals(name, SOURCE.substring(offset, offset + name.length))
                SOURCE.substring(offset).substringAfter("/*").substringBefore("*/")
            }
            .toSet()

    private fun NativeReferenceFixture.complete(
        meaning: RelationMeaning,
        subject: () -> PsiNamedElement,
    ): RelationBatch {
        val read = read(meaning, subject)
        assertTrue("Expected a complete $meaning read, received $read", read is RelationCompilation.Complete)
        return (read as RelationCompilation.Complete).batch
    }

    private fun NativeReferenceFixture.read(
        meaning: RelationMeaning,
        subject: () -> PsiNamedElement,
    ): RelationCompilation = runBlocking {
        val selected = readAction { libraryRequest(meaning, subject()) }
        IntellijRelationCompilerQuery()
            .read(
                project = project,
                currentLease = selected.subject.lease,
                request = selected,
                modelCompilation = model,
            )
    }

    private fun NativeReferenceFixture.libraryRequest(
        meaning: RelationMeaning,
        subject: PsiNamedElement,
    ): RelationRequest {
        val projection = IntellijK2RelationProjection(project, request.subject.lease.workspaceRoot)
        val evidence = (projection.project(subject) as IntellijRelationDeclarationProjection.Projected).evidence
        assertTrue("The subject must be binary", evidence.file is SymbolDiscoveryFileIdentity.External)
        val endpoint =
            RelationEndpoint.resolve(
                lease = request.subject.lease,
                scope =
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_ONLY,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.INCLUDE,
                    ),
                evidence = evidence,
                constraints = request.searchConstraints,
            )
        return RelationRequest.start(
            subject = endpoint.nativeRefined(),
            meaning = meaning,
            budget = request.budget,
            boundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
        )
    }

    private fun function(name: String, vararg: Boolean): PsiNamedElement =
        KotlinTopLevelFunctionFqnNameIndex.get(name, project, GlobalSearchScope.allScope(project)).first {
            it.valueParameters.any { parameter -> parameter.isVarArg } == vararg
        }

    private fun extension(name: String, receiver: String): PsiNamedElement =
        KotlinTopLevelFunctionFqnNameIndex.get(name, project, GlobalSearchScope.allScope(project)).single {
            it.receiverTypeReference?.text?.substringBefore('<')?.substringAfterLast('.') == receiver
        }

    private fun javaMember(owner: String, name: String): PsiNamedElement {
        val type =
            checkNotNull(
                com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(owner, GlobalSearchScope.allScope(project))
            ) {
                "Missing compiled Java class $owner"
            }
        assertTrue("$owner must be compiled", type is com.intellij.psi.impl.compiled.ClsClassImpl)
        return type.findMethodsByName(name, false).singleOrNull()
            ?: checkNotNull(type.findFieldByName(name, false)) {
                "Missing $owner.$name"
            }
    }

    private fun classlike(name: String): PsiNamedElement =
        KotlinFullClassNameIndex.get(name, project, GlobalSearchScope.allScope(project)).single()

    private fun libraryFixture(): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture(callerSource = SOURCE)
            ModuleRootModificationUtil.updateModel(module) { roots ->
                val library = roots.moduleLibraryTable.createLibrary("subject-stdlib").modifiableModel
                val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                library.addRoot("jar://$jar!/", OrderRootType.CLASSES)
                library.commit()
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }
}

private val SINGLE_USES =
    setOf(
        "property",
        "init",
        "lazy",
        "parameter default",
        "stored lambda",
        "constructor default",
        "supertype",
        "reference",
    )

private val VARARG_USES = setOf("vararg")

private const val FILTER = "kotlin.collections.filterNotNull"

private val SOURCE =
    """
    package consumer
    import kotlin.collections.filterNotNull as dropNulls
    val top: List<String> = listOfNotNull("a") /*property*/
    class Holder {
        init { listOfNotNull(1) /*init*/ }
        val delayed by lazy { listOfNotNull(2) /*lazy*/ }
        fun defaulted(all: List<Int> = listOfNotNull(3) /*parameter default*/) = all
    }
    open class Base(val all: List<Int>)
    class Sub(first: List<Int> = listOfNotNull(4) /*constructor default*/) : Base(listOfNotNull(5) /*supertype*/)
    class SourceLazy : Lazy<Int> { override val value = 1; override fun isInitialized() = true }
    object Shadow { fun listOfNotNull(value: Int) = value }
    fun stored(block: () -> Unit): () -> Unit = block
    fun useStored() = stored { listOfNotNull(6) /*stored lambda*/ }
    fun useReference(): (Int?) -> List<Int> = ::listOfNotNull /*reference*/
    fun useVararg() = listOfNotNull(7, 8) /*vararg*/
    fun useShadow() = Shadow.listOfNotNull(9) /*shadow*/
    fun useAlias(all: List<String?>) = all.dropNulls()
    fun usePair() = 1 to 2
    fun useJavaMethod() = java.io.File("x").mkdirs() /*java method*/
    fun useJavaField() = java.io.File.separatorChar /*java field*/
    fun useJavaAccessor() = java.io.File("x").name /*java accessor*/
    fun useJavaGetter() = java.io.File("x").getParentFile() /*java getter*/
    fun useJavaConvention() = java.io.File("a") < java.io.File("b")
    """
        .trimIndent()
