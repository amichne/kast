package io.github.amichne.kast.change.verify.intellij

import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAdmission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntellijRuntimeCompatibilityReviewRegressionTest {
    @Test
    fun reviewRegression_verifierAdmitsOnlyDocumentedHosts() {
        for ((product, build) in listOf("IC" to "262.12345.67", "IU" to "262.1")) {
            assertEquals(
                AddDeclarationIntellijRuntimeAdmission.Supported.IntelliJIdea262,
                admitVerificationIntellijRuntime(product, build),
                "$product-$build",
            )
        }
        assertEquals(
            AddDeclarationIntellijRuntimeAdmission.Supported.AndroidStudio261,
            admitVerificationIntellijRuntime("AI", "261.2"),
        )
        assertEquals(
            AddDeclarationIntellijRuntimeAdmission.Unsupported,
            admitVerificationIntellijRuntime("IC", "261.25134.95"),
        )
    }
}
