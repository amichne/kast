package io.github.amichne.kast.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class KastProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (
            ApplicationManager.getApplication().isUnitTestMode &&
            !java.lang.Boolean.getBoolean("kast.enable.startup.activity.tests")
        ) {
            return
        }
        project.getService(KastProjectService::class.java).start()
    }
}
