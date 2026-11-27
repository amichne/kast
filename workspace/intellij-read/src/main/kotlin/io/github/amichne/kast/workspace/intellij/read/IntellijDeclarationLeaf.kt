package io.github.amichne.kast.workspace.intellij.read

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.compiled.ClsFileImpl

/**
 * The innermost element at [offset] whose parents lead to the declarations that enclose it. A Java class file answers
 * `findElementAt` from its decompiled mirror, whose members have no compiler symbol. Its compiled members report the
 * mirror's ranges, so the innermost compiled class, method, or field that contains [offset] stands in for the leaf.
 */
fun PsiFile.declarationLeafAt(offset: Int): PsiElement? =
    if (this is ClsFileImpl)
        generateSequence(classes.toList()) { level -> level.flatMap { it.innerClasses.toList() } }
            .takeWhile { it.isNotEmpty() }
            .flatten()
            .flatMap { owner -> sequenceOf(owner) + owner.methods + owner.fields }
            .filter { it.textRange?.containsOffset(offset) == true }
            .minByOrNull { it.textRange.length }
    else findElementAt(offset)
