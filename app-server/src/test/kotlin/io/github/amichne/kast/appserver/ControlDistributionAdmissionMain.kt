package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.managed.ControlInventoryAdmission
import io.github.amichne.kast.distribution.managed.ControlPayloadInventory
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

/** Release gate uses the actual runtime owner without creating a state directory. */
object ControlDistributionAdmissionMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = Path.of(arguments.single()).toRealPath()
        val inventory = ControlPayloadInventory.admit(root)
        check(inventory is ControlInventoryAdmission.Admitted) { "release inventory rejected: $inventory" }
        check(BrokerInstallationState.observe(root) == Refinement.Rejected(InstallationStateFailure.EPOCH_ABSENT)) {
            "release runtime identity admission rejected"
        }
        println(
            "Control release admitted: entries=${inventory.traversedEntries}, " +
                "files=${inventory.files.size}, bytes=${inventory.bytes}"
        )
    }
}
