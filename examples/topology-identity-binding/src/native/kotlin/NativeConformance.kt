@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaIdeApi::class)
package kast.example.binding

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Executable assertions to call from Kast's EXISTING imported-project test harness.
 * This function does not open a project, import Gradle, construct a backend, or start an IDE.
 * The caller owns the admitted fixture and read-action/epoch lifecycle. Fixture names below
 * are an explicit test oracle, never production resolution heuristics.
 */
fun verifyNativeFixture(file: KtFile, epoch: Epoch): List<String> {
    fun declaration(owner: String, member: String): KtNamedDeclaration {
        val klass = PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java)
            .single { it.name == owner }
        return klass.declarations.filterIsInstance<KtNamedDeclaration>().single { it.name == member }
    }
    fun reference(function: String, member: String): KtReference {
        val body = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == function }.bodyExpression
            ?: error("fixture function has no body")
        val use = PsiTreeUtil.collectElementsOfType(body, KtNameReferenceExpression::class.java)
            .single { it.getReferencedName() == member }
        return use.references.filterIsInstance<KtReference>().single()
    }
    data class Case(val use: String, val member: String, val owner: String)
    val cases = listOf(
        Case("genericRead", "state", "Feed"),
        Case("stringRead", "state", "Feed"),
        Case("starRead", "state", "Feed"),
        Case("aliasRead", "state", "Feed"),
        Case("stringAccepts", "accepts", "Feed"),
        Case("implementationRead", "state", "StringFeed"),
        Case("inheritedRead", "state", "Feed"),
        Case("inheritedAccepts", "accepts", "Feed"),
    )
    val passed = mutableListOf<String>()
    for (case in cases) {
        val expected = declaration(case.owner, case.member)
        val ref = reference(case.use, case.member)
        // The expected declaration is selected independently, before resolving the use-site.
        val entry = RegistryEntry(epoch, DeclarationId.parse("fixture:${case.owner}.${case.member}"), RegistrySlot.fromOrdinal(expected.textRange.startOffset))
        val result = analyze(ref.element) {
            val target = ref.resolveToSymbol() ?: error("unresolved fixture reference: ${case.use}")
            bindRegisteredSource(entry, epoch, target, RegisteredSourceLookup { candidate ->
                check(candidate == entry)
                RegisteredSourceLookupResult.Found(expected)
            })
        }
        check(result is BindingResult.Complete && result.binding.entry === entry) {
            "native-binding-failed:${case.use}:$result"
        }
        passed += case.use
    }
    val ref = reference("implementationRead", "state")
    val wrong = declaration("Feed", "state")
    val wrongResult = analyze(ref.element) {
        val target = ref.resolveToSymbol() ?: error("unresolved negative fixture reference")
        bindRegisteredSource(RegistryEntry(epoch, DeclarationId.parse("fixture:Feed.state"), RegistrySlot.fromOrdinal(wrong.textRange.startOffset)), epoch, target,
            RegisteredSourceLookup { RegisteredSourceLookupResult.Found(wrong) })
    }
    check(wrongResult is BindingResult.Rejected) { "explicit override collapsed into its base" }
    passed += "explicit-override-is-not-base"
    return passed
}
