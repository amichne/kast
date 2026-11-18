package io.github.amichne.kast.relation.intellij

import com.intellij.codeInsight.ExternalAnnotationsManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReturnStatement
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowSemantics
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class NativeJavaStaticValueFlowTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "java-static-value-flow-jdk"

    override fun setUpProject() {
        super.setUpProject()
        // The class-file mirror can initialize this application-lifetime default-project service.
        ExternalAnnotationsManager.getInstance(ProjectManager.getInstance().defaultProject)
    }

    fun testFormalAliasAndReturnReachOnlyTheOriginatingInvocation() {
        val fixture = fixture()
        val steps = trace(fixture, "aliased")
        val selected = fixture.javaCallable(JAVA, "relay")
        val entry = argument(steps, selected.compilerIdentity, 1)
        val transfers = steps.flatMap { it.transfers }
        val formal = transfers.single { it.kind == ValueTransferKind.SOURCE_CALL_ENTRY && it.source == entry }.target
        val parameter = ((formal.role as ValueRole.Formal).position as ValueFormalPosition.Parameter).parameter
        assertEquals(1, parameter.position.value)
        assertEquals(selected.compilerIdentity, formal.enclosing.compilerIdentity)
        val expected = javaSites(selected.file.stableValue)
        assertEquals(
            expected,
            transfers
                .filter { it.target.enclosing.compilerIdentity == selected.compilerIdentity }
                .map { it.target.range }
                .distinct(),
        )
        val returned = transfers.single { it.kind == ValueTransferKind.SOURCE_CALL_RETURN }
        assertEquals((entry.role as ValueRole.Argument).call.resultSite().identity, returned.target.identity)
        assertTrue(returned.target.callStack.frames.isEmpty())
        assertConsumer(fixture, "aliased", steps)
        val other = fixture.markedExpression(declaration("aliased"), "other")
        assertTrue(
            transfers.none { it.target.range == other.anchor && it.target.enclosing.file == other.enclosing.file }
        )
        assertExhausted(steps)
    }

    fun testZeroPromotionAndBoxingKeepTheJavaReturnUnproven() {
        val fixture = fixture()
        for ((host, method) in
            listOf("zeroDivision" to "zero", "promotedDivision" to "promoted", "boxedDivision" to "boxed")) {
            val steps = trace(fixture, host)
            val failure = steps.flatMap { it.obligations }.single()
            assertEquals(ValueFlowUnsupportedCause.UNMODELED_CALL, failure.cause)
            assertEquals(fixture.javaCallable(JAVA, method).compilerIdentity, failure.site.enclosing.compilerIdentity)
            assertTrue(
                steps
                    .flatMap { it.transfers }
                    .none { transfer ->
                        transfer.kind == ValueTransferKind.PRIMITIVE_DERIVATION ||
                            transfer.kind == ValueTransferKind.SOURCE_CALL_RETURN
                    }
            )
        }
    }

    fun testDirectFormalReturn() {
        val fixture = fixture()
        val steps = trace(fixture, "direct")
        argument(steps, fixture.javaCallable(JAVA, "direct").compilerIdentity, 0)
        assertConsumer(fixture, "direct", steps)
        assertExhausted(steps)
    }

    fun testConstantKeepsArgumentAndFormalWithoutAReturn() {
        val fixture = fixture()
        val steps = trace(fixture, "constant")
        val input = argument(steps, fixture.javaCallable(JAVA, "constant").compilerIdentity, 0)
        assertTrue(
            steps.flatMap { it.transfers }.any { it.source == input && it.kind == ValueTransferKind.SOURCE_CALL_ENTRY }
        )
        assertTrue(steps.flatMap { it.transfers }.none { it.kind == ValueTransferKind.SOURCE_CALL_RETURN })
        assertExhausted(steps)
    }

    fun testUnsupportedSiblingKeepsTheSupportedReturn() {
        val fixture = fixture()
        val steps = trace(fixture, "sibling")
        assertConsumer(fixture, "sibling", steps)
        val unsupported = steps.flatMap { it.obligations }.single()
        assertEquals(ValueFlowUnsupportedCause.UNMODELED_CALL, unsupported.cause)
        assertEquals(fixture.javaCallable(JAVA, "branch").compilerIdentity, unsupported.site.enclosing.compilerIdentity)
        assertEquals(1, steps.flatMap { it.transfers }.count { it.kind == ValueTransferKind.SOURCE_CALL_RETURN })
    }

    fun testCompiledJavaKeepsTheExactCallableAndUnresolvedBody() {
        val root = getOrCreateProjectBaseDir().toNioPath()
        val input = root.resolve("JavaBinary.java")
        val classes = Files.createDirectory(root.resolve("classes"))
        Files.write(input, resource("JavaBinary.java"))
        assertEquals(
            0,
            ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), input.toString()),
        )
        Files.delete(input)
        val entry = "fixture/controls/JavaBinary.class"
        val archive = ByteArrayOutputStream()
        JarOutputStream(archive).use { output ->
            output.putNextEntry(JarEntry(entry))
            Files.copy(classes.resolve(entry), output)
            output.closeEntry()
        }
        val fixture =
            packetFixture(
                listOf(KOTLIN),
                readSource = { resource("JavaBinaryCase.kt") },
            ) {
                val file = getOrCreateProjectBaseDir().createChildData(this, "binary.jar")
                file.setBinaryContent(archive.toByteArray())
                val jarRoot = checkNotNull(JarFileSystem.getInstance().getJarRootForLocalFile(file))
                checkNotNull(jarRoot.findFileByRelativePath(entry))
                ModuleRootModificationUtil.updateModel(module) { model ->
                    val library = model.moduleLibraryTable.createLibrary("compiled-java")
                    val roots = library.modifiableModel
                    roots.addRoot(jarRoot, OrderRootType.CLASSES)
                    roots.commit()
                }
            }
        val steps = trace(fixture, "binary")
        val blocked = steps.flatMap { it.obligations }.single()
        val role = blocked.site.role as ValueRole.Argument
        assertEquals(
            "fixture.controls.JavaBinary.relay",
            role.call.callable.qualifiedIdentity.let {
                (it as io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.Available).value
            },
        )
        assertEquals(ValueFlowUnsupportedCause.EXTERNAL_CALL, blocked.cause)
        assertTrue(steps.flatMap { it.transfers }.none { it.kind == ValueTransferKind.SOURCE_CALL_RETURN })
    }

    private fun fixture() = packetFixture(listOf(KOTLIN, JAVA), readSource = { resource(it.substringAfterLast('/')) })

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/impact-boundary/$name")).use { it.readBytes() }

    private fun declaration(name: String) = PacketDeclaration(KOTLIN, "", name)

    private fun trace(fixture: NativePacketValueFixture, name: String): List<ValueFlowStep> {
        val run =
            ImpactBoundaryTrace(project, fixture, ValueFlowSemantics.KOTLIN_JAVA_FORWARD_V2)
                .run(fixture.selection(declaration(name), "producer", declaration("producer")))
        assertTrue(run.summary(), run.seed is ValueProducerSeedRead.Seeded && run.reads.isNotEmpty())
        assertTrue(run.summary(), run.failures.isEmpty() && run.reads.all { it.read is ValueFlowRead.Observed })
        return run.reads.map { (it.read as ValueFlowRead.Observed).step }
    }

    private fun argument(
        steps: List<ValueFlowStep>,
        callable: io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity,
        position: Int,
    ) =
        steps
            .map { it.source }
            .single { site ->
                val role = site.role as? ValueRole.Argument
                role?.call?.callable?.compilerIdentity == callable && role.position.value == position
            }

    private fun assertConsumer(fixture: NativePacketValueFixture, name: String, steps: List<ValueFlowStep>) {
        val expected = fixture.markedExpression(declaration(name), "tracked")
        val consumer = fixture.callableDeclaration(declaration("consume"))
        val arrival = argument(steps, consumer.compilerIdentity, 0)
        assertEquals(expected.anchor, arrival.range)
        assertEquals(expected.enclosing.compilerIdentity, arrival.enclosing.compilerIdentity)
    }

    private fun assertExhausted(steps: List<ValueFlowStep>) {
        assertTrue(
            steps.flatMap { it.obligations }.toString(),
            steps.all { it.terminal == ValueFlowTerminal.SupportedDomainExhausted },
        )
        assertTrue(steps.all { it.reviewedTransfers.isEmpty() })
    }

    private fun javaSites(path: String) =
        ReadAction.compute<List<io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange>, RuntimeException> {
            val file = checkNotNull(LocalFileSystem.getInstance().findFileByPath(path))
            val java = PsiManager.getInstance(project).findFile(file) as PsiJavaFile
            val method = java.classes.single().findMethodsByName("relay", false).single()
            val (declaration, returned) = checkNotNull(method.body).statements
            val alias = (declaration as PsiDeclarationStatement).declaredElements.single() as PsiLocalVariable
            listOf(
                    method.parameterList.parameters[1],
                    checkNotNull(alias.initializer),
                    alias,
                    checkNotNull((returned as PsiReturnStatement).returnValue),
                )
                .map(::range)
        }

    private companion object {
        const val KOTLIN = "src/main/kotlin/fixture/controls/JavaFlowCases.kt"
        const val JAVA = "src/main/kotlin/fixture/controls/JavaFlow.java"
    }
}
