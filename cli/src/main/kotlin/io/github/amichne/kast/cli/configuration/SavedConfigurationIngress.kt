package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.managed.SavedConfigurationIngressRejection

internal class SavedConfigurationIngressCompositionFailure(val rejection: SavedConfigurationIngressRejection) :
    KastCliCompositionFailure {
    override val outputReason: String = "saved-configuration-${rejection.reason().lowercase()}"
}
