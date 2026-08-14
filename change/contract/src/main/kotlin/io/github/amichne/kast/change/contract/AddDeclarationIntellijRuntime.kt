package io.github.amichne.kast.change.contract

sealed interface AddDeclarationIntellijRuntimeAdmission {
    sealed interface Supported : AddDeclarationIntellijRuntimeAdmission {
        data object IntelliJIdea262 : Supported

        data object AndroidStudio261 : Supported
    }

    data object Unsupported : AddDeclarationIntellijRuntimeAdmission

    companion object {
        /**
         * Proof transition:
         * `(String, String) -> AddDeclarationIntellijRuntimeAdmission`.
         *
         * `Supported` proves one documented production host: IntelliJ IDEA build 262 or Android
         * Studio build 261. `Unsupported` is the closed expected failure. Raw product/build
         * strings may be extracted only at the IntelliJ application boundary.
         */
        fun admit(
            productCode: String,
            build: String,
        ): AddDeclarationIntellijRuntimeAdmission {
            val branch = BUILD_NUMBER.matchEntire(build)?.groupValues?.get(1)
                         ?: return Unsupported
            return when {
                productCode in INTELLIJ_IDEA_PRODUCTS && branch == INTELLIJ_IDEA_BRANCH ->
                    Supported.IntelliJIdea262
                productCode == ANDROID_STUDIO_PRODUCT && branch == ANDROID_STUDIO_BRANCH ->
                    Supported.AndroidStudio261
                else -> Unsupported
            }
        }

        private val BUILD_NUMBER = Regex("([0-9]+)(?:\\.[0-9]+)*")
        private val INTELLIJ_IDEA_PRODUCTS = setOf("IC", "IU")
        private const val INTELLIJ_IDEA_BRANCH = "262"
        private const val ANDROID_STUDIO_PRODUCT = "AI"
        private const val ANDROID_STUDIO_BRANCH = "261"
    }
}

/**
 * Typed authority for a runtime admission that has already crossed the raw IntelliJ build
 * boundary.
 *
 * Production authorities must derive the result through [AddDeclarationIntellijRuntimeAdmission.admit].
 * Tests may supply a documented supported-host capability without teaching production admission
 * about the test framework's host distribution.
 */
fun interface AddDeclarationIntellijRuntimeAuthority {
    fun current(): AddDeclarationIntellijRuntimeAdmission
}
