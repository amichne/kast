package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached quota accounting from explicit fields; this does not measure native search or JVM heap. */
class RelationProviderRetentionTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(ROOT)).value()
    private val file =
        SymbolDiscoveryFileIdentity.Workspace(CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of(FILE)).value())

    @Test
    fun `1001 native reference locators charge UTF16 storage and bounded object overhead`() {
        val descriptors =
            (0 until 1_001).map { ordinal ->
                "reference:org.jetbrains.kotlin.idea.references.KtSimpleNameReference\u0000" +
                    "file://$FILE\u0000${100 + ordinal * 32}\u0000${120 + ordinal * 32}"
            }
        val locators = descriptors.mapIndexed { ordinal, descriptor ->
            RelationProviderLocator.Reference(
                file,
                ExactDeclarationTextRange.parse(100 + ordinal * 32, 120 + ordinal * 32).value(),
                RelationProviderItemDescriptor.parse(descriptor).value(),
            )
        }
        val expectedBytes = 512L + descriptors.sumOf { 512L + 2L * (FILE.length + it.length) }
        val retained = RelationProviderState.references(locators)
        assertEquals(expectedBytes, retained.retainedBytes)
        assertTrue(expectedBytes < 1_200_000L)
        assertEquals(locators, retained.prepared)
        val successor = retained.consume()
        assertEquals(expectedBytes, successor.retainedBytes)
        assertEquals(locators.drop(1), successor.prepared)
        assertEquals(retained.providerCursor.advance(locators.first().descriptor), successor.providerCursor)
    }

    @Test
    fun `native definition and call estimates preserve every detached identity and UTF16 code unit`() {
        val descriptor = RelationProviderItemDescriptor.parse("native:\u03bb\uD83D\uDE00").value()
        val range = ExactDeclarationTextRange.parse(1, 2).value()
        val elementClass = RelationProviderElementClass.parse("fixture.NativeElement").value()
        val providerPath = "$ROOT/src/main/java/fixture/NavigationProvider.java"
        val providerFile =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of(providerPath)).value()
            )
        val baseTextUnits = FILE.length.toLong() + descriptor.value.length
        val definitions =
            listOf(
                RelationProviderLocator.Definition.Normalized(file, range, descriptor, elementClass, providerFile),
                RelationProviderLocator.Definition.Unsupported(file, range, descriptor, elementClass, providerFile),
            )
        for (definition in definitions) {
            assertEquals(
                512L + 2L * (baseTextUnits + elementClass.value.length + providerPath.length),
                definition.retainedBytes,
            )
            assertEquals(providerFile, definition.providerFile)
        }
        assertEquals(
            512L + 2L * baseTextUnits,
            RelationProviderLocator.Callee.Reference(file, range, descriptor).retainedBytes,
        )
        assertEquals(
            512L + 2L * (baseTextUnits + elementClass.value.length),
            RelationProviderLocator.Callee.UnresolvedCall(file, range, descriptor, elementClass).retainedBytes,
        )
    }

    private fun <Value, Failure> Refinement<Value, Failure>.value(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected detached retention fixture, got $failure")
        }
}

private const val ROOT = "/private/tmp/kast-semantic-native-oals0m41/fixture"
private const val FILE = "$ROOT/src/main/kotlin/fixture/references/ReadDenseReferences.kt"
