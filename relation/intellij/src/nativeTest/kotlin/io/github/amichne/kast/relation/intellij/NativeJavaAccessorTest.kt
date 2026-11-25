package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Kotlin property syntax calls Java accessors without naming them. Native reference search does not enumerate those
 * sites, so an incoming read of an accessor that Kotlin source can use stays qualified.
 */
class NativeJavaAccessorTest : NativeConventionFixture() {
    override val fixtureSdkName = "java-accessor-jdk"

    fun testPropertySyntaxCalleesIncludeTheSelectedAccessors() {
        val fixture = accessorFixture()
        val actual = EXPECTED_CALLEES.keys.associateWith { rows(fixture.relation(it, RelationMeaning.Callees)) }
        assertEquals(EXPECTED_CALLEES.mapValues { (_, callees) -> Rows.Complete(callees) }, actual)
    }

    fun testIncomingAccessorReadsFailClosedWhenKotlinCanUseTheProperty() {
        val fixture = accessorFixture()
        val actual =
            INCOMING.flatMap { meaning ->
                    ACCESSORS.map { (it to meaning) to rows(fixture.relation(meaning) { javaMethod(it) }) }
                }
                .toMap()
        assertEquals(actual.mapValues { Rows.Incomplete }, actual)
    }

    /** A Kotlin override of a Java accessor stays usable through the Java property. */
    fun testIncomingReadsOfAKotlinOverrideFailClosed() {
        val fixture = accessorFixture()
        for (meaning in INCOMING) {
            assertEquals(Rows.Incomplete, rows(fixture.relation("Sub.getLabel", meaning)))
        }
    }

    /** The property name, the parameter list, and the static modifier each prove that no property use exists. */
    fun testMethodsThatKotlinCannotUseAsPropertiesStayComplete() {
        val fixture = accessorFixture()
        val expected = mapOf("getUnread" to "callsUnread", "getKeyed" to "callsKeyed", "getShared" to "callsShared")
        val actual = INCOMING.flatMap { meaning ->
            expected.keys.map { (it to meaning) to rows(fixture.relation(meaning) { javaMethod(it) }) }
        }
        assertEquals(actual.map { (key, _) -> key to Rows.Complete(setOf(expected.getValue(key.first))) }, actual)
    }

    /** A Kotlin function that overrides nothing from Java is not a Java property accessor. */
    fun testAKotlinFunctionWithAnAccessorNameStaysComplete() {
        val fixture = accessorFixture()
        for (meaning in INCOMING) {
            assertEquals(Rows.Complete(setOf("callsPlain")), rows(fixture.relation("Plain.getLabel", meaning)))
        }
    }

    /** The callee of a call has its own item, so an invoked property value qualifies the read there. */
    fun testAnInvokedPropertyValueFailsClosed() {
        assertEquals(Rows.Incomplete, rows(accessorFixture().relation("invokesProperty", RelationMeaning.Callees)))
    }

    /** A callable reference calls nothing. It stays a retained callable for the callback proof. */
    fun testAPropertyReferenceIsARetainedCallable() {
        val batch = accessorFixture().complete("refersToProperty", RelationMeaning.Callees)
        assertEquals(0, batch.facts.size)
        assertEquals(1, batch.callableObservations.size)
    }

    /** The resolved role proves that the empty callees of `readsPlatform` come from a compiled accessor. */
    fun testACompiledAccessorIsOutsideTheSourceDomain() {
        val fixture = accessorFixture()
        val selected =
            ApplicationManager.getApplication().runReadAction<Pair<List<ConventionCallRole>, ConventionCallTarget>> {
                val projection = IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot)
                val file = checkNotNull(psiManager.findFile(fixture.callerFile))
                val site =
                    PsiTreeUtil.findElementOfClassAtOffset(
                        file,
                        KOTLIN.indexOf("thread.name") + "thread.".length,
                        KtNameReferenceExpression::class.java,
                        false,
                    )
                val roles = projection.javaAccessorRoles(checkNotNull(site))
                roles to projection.resolveConvention(site.mainReference, roles.single())
            }
        assertEquals(listOf(ConventionCallRole.JAVA_GETTER) to ConventionCallTarget.Binary, selected)
        assertEquals(0, fixture.complete("readsPlatform", RelationMeaning.Callees).scopeExclusions.size)
    }

    private sealed interface Rows {
        data class Complete(val names: Set<String>) : Rows

        data object Incomplete : Rows
    }

    private fun rows(read: RelationCompilation): Rows =
        when (read) {
            is RelationCompilation.Complete -> {
                val callees = read.batch.request.meaning == RelationMeaning.Callees
                Rows.Complete(
                    read.batch.facts.map { if (callees) it.target.name.value else it.source.name.value }.toSet()
                )
            }
            is RelationCompilation.Qualified -> Rows.Incomplete
            is RelationCompilation.Rejected -> error("Accessor read returned ${read.reason}")
        }

    private fun accessorFixture(): NativeReferenceFixture {
        val fixture = conventionFixture(KOTLIN)
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val directory = fixture.callerFile.parent.createChildDirectory(this, "consumer")
                directory.createChildData(this, "Bean.java").setBinaryContent(JAVA.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }

    private fun javaMethod(name: String): PsiMethod {
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath("src/consumer/Bean.java"))
        val bean: PsiClass = (psiManager.findFile(file) as PsiJavaFile).classes.single()
        return bean.findMethodsByName(name, false).single()
    }
}

private val INCOMING = listOf(RelationMeaning.Callers, RelationMeaning.References)

private val ACCESSORS = listOf("getLabel", "setLabel", "isArchived", "setArchived", "getTotal", "setTotal")

private val EXPECTED_CALLEES: Map<String, Set<String>> =
    mapOf(
        "readsLabel" to setOf("getLabel"),
        "readsSafely" to setOf("getLabel"),
        "readsImplicitly" to setOf("getLabel"),
        "readsOverride" to setOf("getLabel"),
        "readsArchived" to setOf("isArchived"),
        "writesLabel" to setOf("setLabel"),
        "writesArchived" to setOf("setArchived"),
        "writesParenthesized" to setOf("setLabel"),
        "bumpsTotal" to setOf("getTotal", "setTotal"),
        "incrementsTotal" to setOf("getTotal", "setTotal"),
        // The operator mutates the value that the getter returned, so no setter runs.
        "addsToLedger" to setOf("getLedger", "plusAssign"),
        "callsMethods" to setOf("getLabel", "setLabel"),
        "readsComponent" to setOf("component"),
        // A compiled accessor is outside a source domain, as a binary operator is.
        "readsPlatform" to emptySet(),
        // A Kotlin property and a Java field run no Java accessor.
        "readsKotlin" to emptySet(),
        "readsField" to emptySet(),
    )

private val JAVA =
    """
    package consumer;
    public class Bean {
        public int field;
        public String getLabel() { return ""; }
        public void setLabel(String label) {}
        public boolean isArchived() { return false; }
        public void setArchived(boolean archived) {}
        public int getTotal() { return 0; }
        public void setTotal(int total) {}
        public Ledger getLedger() { return null; }
        public void setLedger(Ledger ledger) {}
        public String getUnread() { return ""; }
        public String getKeyed(String key) { return key; }
        public static String getShared() { return ""; }
        public Adder getAdder() { return null; }
        public record Pair(int component) {}
    }
    """
        .trimIndent()

private val KOTLIN =
    """
    package consumer
    class Ledger { operator fun plusAssign(amount: Int) {} }
    class Sub : Bean() { override fun getLabel(): String = "sub" }
    class Plain(val keyed: Int, val shared: Int) { fun getLabel() = keyed }
    fun readsLabel(bean: Bean) = bean.label
    fun readsSafely(bean: Bean?) = bean?.label
    fun Bean.readsImplicitly() = label
    fun readsOverride(sub: Sub) = sub.label
    fun readsArchived(bean: Bean) = bean.isArchived
    fun writesLabel(bean: Bean) { bean.label = "x" }
    fun writesArchived(bean: Bean) { bean.isArchived = true }
    fun writesParenthesized(bean: Bean) { (bean.label) = "x" }
    fun bumpsTotal(bean: Bean) { bean.total += 1 }
    fun incrementsTotal(bean: Bean) { bean.total++ }
    fun addsToLedger(bean: Bean) { bean.ledger += 1 }
    fun callsMethods(bean: Bean) { bean.getLabel(); bean.setLabel("x") }
    fun callsUnread(bean: Bean) = bean.getUnread()
    fun callsKeyed(bean: Bean) = bean.getKeyed("a")
    fun callsShared() = Bean.getShared()
    fun callsPlain(plain: Plain) = plain.getLabel()
    class Adder { operator fun invoke(amount: Int) = amount }
    fun invokesProperty(bean: Bean) = bean.adder(1)
    fun refersToProperty(bean: Bean) = bean::label
    fun readsComponent(pair: Bean.Pair) = pair.component
    fun readsPlatform(thread: Thread) = thread.name
    fun readsKotlin(plain: Plain) = plain.keyed
    fun readsField(bean: Bean) = bean.field
    """
        .trimIndent()
