package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.contract.PlannedSourcePrecondition
import io.github.amichne.kast.change.contract.SourceTextMutation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeFilePreview
import io.github.amichne.kast.protocol.contract.ChangeFilePreviewKind
import io.github.amichne.kast.protocol.contract.ChangeFilePreviewSet
import io.github.amichne.kast.protocol.contract.ChangePreviewDiff
import io.github.amichne.kast.protocol.contract.ChangePreviewPath
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.file.Path

/** Pure projection of a compiler-grounded change plan into bounded human diff fragments. */
internal fun ChangePlan.protocolPreview(): ChangeFilePreviewSet {
    val root = Path.of(priorLease.workspaceRoot.value)
    val previews = writes.entries.map { write ->
        val relative = when (val admitted = WorkspaceSourcePath.parse(
            root.relativize(Path.of(write.source.path.value)).toString().replace('\\', '/'),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("planned write escaped its proven workspace root")
        }
        ChangeFilePreview(
            path = when (val admitted = ChangePreviewPath.parse(relative.value)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> error("workspace-relative preview path was rejected")
            },
            kind = when (write.precondition) {
                PlannedSourcePrecondition.Absent -> ChangeFilePreviewKind.ADD
                is PlannedSourcePrecondition.Existing -> ChangeFilePreviewKind.UPDATE
            },
            diff = write.mutations.joinToString("\n") { mutation -> mutation.diffFragment() }
                .protocolPreviewDiff(),
        )
    }
    return when (val admitted = ChangeFilePreviewSet.admit(previews)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error("semantic change plan must contain unique non-empty writes")
    }
}

private fun SourceTextMutation.diffFragment(): String = when (this) {
    is SourceTextMutation.CreateFile -> content.value
        .removeSuffix("\n")
        .lineSequence()
        .joinToString("\n") { line -> "+$line" }
    is SourceTextMutation.InsertAfterDeclaration -> buildString {
        appendLine("@@ insert after declaration @${anchor.endExclusive} @@")
        append(declaration.value.addedLines())
    }
    is SourceTextMutation.InsertIntoClassBody -> buildString {
        appendLine("@@ insert into class body @${anchor.endExclusive} @@")
        append(declaration.value.addedLines())
    }
    is SourceTextMutation.Replace -> buildString {
        appendLine("@@ replace symbol @${range.startInclusive}..${range.endExclusive} @@")
        appendLine("-${expected.value}")
        append("+${replacement.value}")
    }
    is SourceTextMutation.ReplaceDeclaration -> buildString {
        appendLine("@@ replace declaration @${range.startInclusive}..${range.endExclusive} @@")
        appendLine(expected.value.removedLines())
        append(replacement.value.addedLines())
    }
}

private fun String.addedLines(): String = lineSequence().joinToString("\n") { line -> "+$line" }

private fun String.removedLines(): String = lineSequence().joinToString("\n") { line -> "-$line" }

private fun String.protocolPreviewDiff(): ChangePreviewDiff = when (
    val parsed = ChangePreviewDiff.parse(this)
) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error("admitted semantic change exceeded the public preview boundary")
}
