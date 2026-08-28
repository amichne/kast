package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp013ReceiptProgression(
    program: DeliveryProgram,
    compatibility: TaskReceiptRegistration,
    configureCompatibility: Kvp012ReceiptTaskBase.() -> Unit,
): TaskId {
    val projection = taskReceiptRegistration(program, TaskId("KVP-005"))
    val endpoint = taskReceiptRegistration(program, TaskId("KVP-013"))
    val endpointSchemaPath = "gradle/delivery/schema/ide-endpoint.schema.json"
    val negativeTestPath =
        "protocol/wire/src/test/kotlin/io/github/amichne/kast/protocol/wire/metadata/" +
            "IdeEndpointDescriptorNegativeTest.kt"
    val positiveTestPath =
        "protocol/wire/src/test/kotlin/io/github/amichne/kast/protocol/wire/metadata/" +
            "IdeEndpointDescriptorTest.kt"

    fun Kvp013ReceiptTaskBase.configureEndpoint() {
        configureCompatibility()
        endpointTaskId.set(endpoint.task.id.value)
        endpointRedGateId.set(endpoint.redGate.id)
        endpointGreenGateId.set(endpoint.greenGate.id)
        endpointCompletionGateId.set(endpoint.completionGate.id)
        endpointRedReceiptId.set(endpoint.redGate.outputReceiptId)
        endpointGreenReceiptId.set(endpoint.greenGate.outputReceiptId)
        endpointCompletionReceiptId.set(endpoint.completionGate.outputReceiptId)
        endpointRedCommand.set(endpoint.redGate.command)
        endpointGreenCommand.set(endpoint.greenGate.command)
        endpointCompletionCommand.set(endpoint.completionGate.command)
        endpointTaskInputDigest.set(endpoint.taskInputDigest)
        endpointCompletionInputDigest.set(endpoint.completionInputDigest)
        endpointProofReportPath.set(endpoint.task.outputs.single().path)
        this.endpointSchemaPath.set(endpointSchemaPath)
        endpointNegativeTestPath.set(negativeTestPath)
        endpointPositiveTestPath.set(positiveTestPath)

        directProjectionRedReceiptFile.set(projection.redReceipt)
        directProjectionGreenReceiptFile.set(projection.greenReceipt)
        directProjectionProofReportFile.set(projection.proofReport)
        directProjectionCompletionReceiptFile.set(projection.completionReceipt)
        directCompatibilityRedReceiptFile.set(compatibility.redReceipt)
        directCompatibilityGreenReceiptFile.set(compatibility.greenReceipt)
        directCompatibilityProofReportFile.set(compatibility.proofReport)
        directCompatibilityCompletionReceiptFile.set(compatibility.completionReceipt)
        endpointSchemaFile.set(layout.projectDirectory.file(endpointSchemaPath))
        endpointNegativeTestFile.set(layout.projectDirectory.file(negativeTestPath))
        endpointPositiveTestFile.set(layout.projectDirectory.file(positiveTestPath))
    }

    val recordRed = tasks.register(
        "recordKVP013RedReceipt",
        RecordKvp013RedReceiptTask::class.java,
    ) {
        configureEndpoint()
        dependsOn("verifyKVP005CompletionReceipt", "verifyKVP012CompletionReceipt")
        receiptFile.set(endpoint.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP013GreenReceipt",
        RecordKvp013GreenReceiptTask::class.java,
    ) {
        configureEndpoint()
        dependsOn(recordRed)
        redReceiptFile.set(endpoint.redReceipt)
        proofReportFile.set(endpoint.proofReport)
        receiptFile.set(endpoint.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP013Completion",
        DeriveKvp013CompletionReceiptTask::class.java,
    ) {
        configureEndpoint()
        dependsOn(recordGreen)
        mustRunAfter(":protocol:wire:generateIdeEndpointDescriptorReport")
        redReceiptFile.set(endpoint.redReceipt)
        greenReceiptFile.set(endpoint.greenReceipt)
        proofReportFile.set(endpoint.proofReport)
        receiptFile.set(endpoint.completionReceipt)
    }
    tasks.register(
        "verifyKVP013CompletionReceipt",
        VerifyKvp013CompletionReceiptTask::class.java,
    ) {
        configureEndpoint()
        dependsOn(derive)
        redReceiptFile.set(endpoint.redReceipt)
        greenReceiptFile.set(endpoint.greenReceipt)
        proofReportFile.set(endpoint.proofReport)
        completionReceiptFile.set(endpoint.completionReceipt)
    }
    return endpoint.task.id
}
