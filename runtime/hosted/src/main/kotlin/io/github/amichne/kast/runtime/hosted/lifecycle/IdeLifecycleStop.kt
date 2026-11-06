package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationManagerEx

/** The existing settlement acknowledgment authorizes this normal, vetoable exit of the selected Host. */
internal fun requestSettledHostExit() {
    val application = ApplicationManager.getApplication()
    application.invokeLater {
        if (ManagedHeadlessHost.current() == null) application.exit()
        else ApplicationManagerEx.getApplicationEx().exit(false, true)
    }
}
