package io.github.amichne.kast.change.apply.intellij

import com.intellij.openapi.application.ApplicationInfo
import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAdmission
import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAuthority

/**
 * Proof transition: `(String, String) -> AddDeclarationIntellijRuntimeAdmission`.
 *
 * Supported proves that the IntelliJ product and branch belong to Kast's supported host matrix.
 * Unsupported is the only closed contrary state. Raw build strings remain at this adapter edge.
 */
internal fun admitIntellijRuntime(
    productCode: String,
    build: String,
): AddDeclarationIntellijRuntimeAdmission =
    AddDeclarationIntellijRuntimeAdmission.admit(productCode, build)

/**
 * Proof transition: live IntelliJ build authority to [AddDeclarationIntellijRuntimeAuthority].
 *
 * The returned authority re-observes and admits the current product/build at each application
 * boundary. Raw platform strings are extracted only inside this adapter.
 */
internal fun liveIntellijRuntimeAuthority(): AddDeclarationIntellijRuntimeAuthority =
    AddDeclarationIntellijRuntimeAuthority {
        val build = ApplicationInfo.getInstance().build
        admitIntellijRuntime(build.productCode, build.asStringWithoutProductCode())
    }

internal const val ADD_DECLARATION_COMMAND_NAME: String = "Kast add declaration"
internal const val ADD_DECLARATION_COMMAND_GROUP: String = "kast.add-declaration"
