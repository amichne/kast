package io.github.amichne.kast.change.verify.intellij

import com.intellij.openapi.application.ApplicationInfo
import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAdmission
import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAuthority

/**
 * Proof transition: `(String, String) -> AddDeclarationIntellijRuntimeAdmission`.
 *
 * Supported proves that the IntelliJ product and branch belong to Kast's supported host matrix.
 * Unsupported is the only closed contrary state. Raw build strings remain at this adapter edge.
 */
internal fun admitVerificationIntellijRuntime(
    productCode: String,
    build: String,
): AddDeclarationIntellijRuntimeAdmission =
    AddDeclarationIntellijRuntimeAdmission.admit(productCode, build)

/**
 * Proof transition: live IntelliJ build authority to [AddDeclarationIntellijRuntimeAuthority].
 *
 * The returned authority re-observes and admits the current product/build at each verification
 * boundary. Raw platform strings are extracted only inside this adapter.
 */
internal fun liveVerificationRuntimeAuthority(): AddDeclarationIntellijRuntimeAuthority =
    AddDeclarationIntellijRuntimeAuthority {
        val build = ApplicationInfo.getInstance().build
        admitVerificationIntellijRuntime(build.productCode, build.asStringWithoutProductCode())
    }
