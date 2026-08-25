package kast

import support.delivery.GenerateDeliveryProjectionsTask
import support.delivery.KastVfsPassiveReusedIndexProgram
import support.delivery.VerifyDeliveryProjectionsTask
import support.delivery.canonicalJson

plugins { base }

val program = KastVfsPassiveReusedIndexProgram.validated
val checkedInProgramProjectionFile =
    layout.projectDirectory.file("gradle/delivery/kast-vfs-passive-reused-index-program.json")
val checkedInRequirementTraceFile =
    layout.projectDirectory.file("gradle/delivery/kast-vfs-passive-requirements.json")
val expectedProgramProjectionContent = canonicalJson(program.projection()) + "\n"
val expectedRequirementTraceContent = canonicalJson(program.requirementTraceProjection()) + "\n"
val receiptDirectory = layout.projectDirectory.dir("gradle/delivery/receipts")

tasks.register<GenerateDeliveryProjectionsTask>("generateKastVfsPassiveProjection") {
    group = "verification"
    programProjection.set(expectedProgramProjectionContent)
    programOutputFile.set(checkedInProgramProjectionFile)
    requirementTraceProjection.set(expectedRequirementTraceContent)
    requirementTraceOutputFile.set(checkedInRequirementTraceFile)
}

tasks.register<VerifyDeliveryProjectionsTask>("verifyKastVfsPassiveProjection") {
    group = "verification"
    mustRunAfter("generateKastVfsPassiveProjection")
    expectedProgramProjection.set(expectedProgramProjectionContent)
    programProjectionFile.set(checkedInProgramProjectionFile)
    expectedRequirementTraceProjection.set(expectedRequirementTraceContent)
    requirementTraceProjectionFile.set(checkedInRequirementTraceFile)
}

program.program.tasks.sortedBy { it.id }.forEach { node ->
    val redReceipt = receiptDirectory.file("${node.red.gateId}-RECEIPT.receipt.json")
    val greenReceipt = receiptDirectory.file("${node.green.gateId}-RECEIPT.receipt.json")
    tasks.register("record${node.id.value.replace("-", "")}RedReceipt") {
        group = "verification"
        dependsOn(node.red.command.removePrefix("./gradlew ").split(' '))
        inputs.file(checkedInProgramProjectionFile)
        inputs.file(checkedInRequirementTraceFile)
        node.dependencies.taskIds.forEach { dep -> inputs.file(receiptDirectory.file("${dep.value}-COMPLETE.receipt.json")) }
        outputs.file(redReceipt)
        doLast { error("RecordGateReceiptTask must bind exact head, command, inputs, observations, artifacts, and dependency receipt digests before writing ${redReceipt.asFile}") }
    }
    tasks.register("record${node.id.value.replace("-", "")}GreenReceipt") {
        group = "verification"
        dependsOn(node.green.command.removePrefix("./gradlew ").split(' '))
        inputs.file(checkedInProgramProjectionFile)
        inputs.file(checkedInRequirementTraceFile)
        inputs.file(redReceipt)
        node.dependencies.taskIds.forEach { dep -> inputs.file(receiptDirectory.file("${dep.value}-COMPLETE.receipt.json")) }
        outputs.file(greenReceipt)
        doLast { error("RecordGateReceiptTask must verify all evidence before writing ${greenReceipt.asFile}") }
    }
}

tasks.register("deriveKastVfsPassiveState") {
    group = "verification"
    dependsOn("verifyKastVfsPassiveProjection")
    inputs.dir(receiptDirectory)
    outputs.file(layout.buildDirectory.file("reports/delivery/kast-vfs-passive-state.json"))
    doLast { error("DeriveDeliveryStateTask must admit receipts and derive state; no status field may be read") }
}

tasks.register("proveBestCaseVfsPassiveReusedIndex") {
    group = "verification"
    dependsOn("deriveKastVfsPassiveState", "ideHostedSpecificationRevalidation")
    doLast { error("Terminal task must emit BestCaseVfsPassiveReusedIndex only from the complete exact-head receipt closure") }
}
