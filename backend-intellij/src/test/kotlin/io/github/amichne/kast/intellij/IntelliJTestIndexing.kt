package io.github.amichne.kast.intellij

import com.intellij.openapi.project.Project
import com.intellij.testFramework.IndexingTestUtil

internal fun waitUntilIndexesAreReady(project: Project) {
    IndexingTestUtil.waitUntilIndexesAreReady(project)
}
