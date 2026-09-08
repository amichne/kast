package io.github.amichne.kast.appserver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DesktopAttachmentTest {
    @Test fun `unknown builds and competing discovery mechanisms are explicit incompatibilities`() {
        assertEquals(DesktopAttachmentAdmission.Eligible,DesktopAttachmentPolicy.admit(emptyMap(),"26.901.51231",false))
        assertEquals(DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.VERSION_UNSUPPORTED),DesktopAttachmentPolicy.admit(emptyMap(),"next",false))
        for (overrides in listOf(mapOf("CODEX_CLI_PATH" to "/other"),mapOf("CODEX_APP_SERVER_FORCE_CLI" to "1"))) {
            assertEquals(DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.OVERRIDE_CONFLICT),DesktopAttachmentPolicy.admit(overrides,"26.901.51231",false))
        }
        assertEquals(DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.OVERRIDE_CONFLICT),DesktopAttachmentPolicy.admit(emptyMap(),"26.901.51231",true))
    }
}
