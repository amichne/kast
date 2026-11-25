@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.PresentedWorkspaceRoot
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.contract.WORKSPACE_FILE_SERIAL_NAME
import io.github.amichne.kast.protocol.contract.WORKSPACE_FILE_TEXT_SERIAL_NAME
import io.github.amichne.kast.protocol.contract.WorkspaceFileSerializer
import io.github.amichne.kast.protocol.contract.WorkspaceFileTextSerializer
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceFilePresentationTest {
    private val root = PresentedWorkspaceRoot.fromCanonical("/work/app")

    @Test
    fun `only a file below the root becomes relative`() {
        val cases =
            listOf(
                "/work/app/src/Main.kt" to "src/Main.kt",
                "/work/app" to "/work/app",
                "/work/app/" to "/work/app/",
                "/work/application/Main.kt" to "/work/application/Main.kt",
                "/cache/lib.jar!/Main.class" to "/cache/lib.jar!/Main.class",
                "src/Main.kt" to "src/Main.kt",
            )
        for ((file, expected) in cases) assertEquals(expected, root.present(file), file)
        assertEquals("work/Main.kt", PresentedWorkspaceRoot.fromCanonical("/").present("/work/Main.kt"))
    }

    @Test
    fun `a presentation changes file fields and nothing else and keeps its detail`() {
        val path = "/work/app/src/Main.kt"
        val value = Sample(path, text(path), path)
        val absolute = mapOf("file" to path, "body" to path, "source" to path)
        val relative = mapOf("file" to "src/Main.kt", "body" to "src/Main.kt", "source" to path)
        val transported = Json.encodeToString(Sample.serializer(), value)
        assertEquals(absolute, fields(transported))
        assertEquals(value, Json.decodeFromString(Sample.serializer(), transported))

        val document =
            CanonicalJsonDocument.generated(Sample.serializer()) { it.copy(source = "compact") }.create(value)
        assertEquals(absolute, fields(document.value))
        assertEquals(relative, fields(document.relativeTo(root).value))
        val compact = relative + ("source" to "compact")
        assertEquals(compact, fields(document.relativeTo(root).present(ToolOutputDetail.COMPACT).value))
        assertEquals(compact, fields(document.present(ToolOutputDetail.COMPACT).relativeTo(root).value))
        for (outcome in
            listOf(
                ProjectedOperationOutcome.Complete(document),
                ProjectedOperationOutcome.Qualified(document),
                ProjectedOperationOutcome.Rejected(document),
            )) {
            val presented = outcome.relativeTo(root)
            assertEquals(outcome::class, presented::class)
            assertEquals(relative, fields(presented.document().value))
        }
    }

    @Test
    fun `every file field of a query document is presented relative to the root`() {
        val files =
            with(CanonicalQueryCliDocuments) {
                val visited = mutableSetOf<String>()
                listOf(completeSerializer, qualifiedSerializer, rejectedSerializer).flatMap {
                    fileFields(it.descriptor, visited)
                }
            }
        assertTrue(files.size >= 8, "found only ${files.size} file fields")
        // A location source repeats its input, which admission already requires to be relative.
        val absolute = files.filterNot { (_, field) -> field in presented }.map { (owner, _) -> owner }
        assertEquals(setOf("location"), absolute.toSet())
    }

    /** Each owner of a string field named `file`, with the serial name of that field. */
    private fun fileFields(descriptor: SerialDescriptor, visited: MutableSet<String>): List<Pair<String, String>> {
        val elements =
            (0 until descriptor.elementsCount).map {
                descriptor.getElementName(it) to descriptor.getElementDescriptor(it)
            }
        // Every list shares one serial name, and a polymorphic tag is unique only within its owner.
        val declared = descriptor.kind == StructureKind.CLASS || descriptor.kind is PolymorphicKind
        val identity = descriptor.serialName + elements.map { it.first }
        if (descriptor.kind is PrimitiveKind || declared && !visited.add(identity)) return emptyList()
        val own =
            elements
                .filter { (name, element) -> name == "file" && element.kind == PrimitiveKind.STRING }
                .map { (_, element) -> descriptor.serialName to element.serialName }
        return own + elements.flatMap { (_, element) -> fileFields(element, visited) }
    }

    @Serializable
    private data class Sample(
        @Serializable(with = WorkspaceFileSerializer::class) val file: String,
        @Serializable(with = WorkspaceFileTextSerializer::class) val body: ProtocolText,
        val source: String,
    )

    private fun ProjectedOperationOutcome.document(): CanonicalJsonDocument =
        when (this) {
            is ProjectedOperationOutcome.Complete -> document
            is ProjectedOperationOutcome.Qualified -> document
            is ProjectedOperationOutcome.Rejected -> document
        }

    private fun fields(document: String): Map<String, String> =
        Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), document)

    private fun text(raw: String): ProtocolText = (ProtocolText.parse(raw) as Refinement.Refined).value

    private companion object {
        val presented = setOf(WORKSPACE_FILE_SERIAL_NAME, WORKSPACE_FILE_TEXT_SERIAL_NAME)
    }
}
