package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.testFramework.IndexingTestUtil

internal fun waitUntilIndexesAreReady(project: Project) {
    IndexingTestUtil.waitUntilIndexesAreReady(project)
}
