package support.delivery

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

internal fun Project.registerKvp018ReceiptProgression(
    program: DeliveryProgram,
    signalLedger: TaskReceiptRegistration,
    detached: TaskReceiptRegistration,
    readEpoch: TaskReceiptRegistration,
    configureSignalLedger: Kvp015ReceiptTaskBase.() -> Unit,
): Set<TaskId> {
    val hosted = taskReceiptRegistration(program, TaskId("KVP-018"))
    val contractMainRoot =
        "workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/"
    val contractTestRoot =
        "workspace/contract/src/test/kotlin/io/github/amichne/kast/workspace/contract/"
    val adapterMainRoot =
        "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/"
    val adapterTestRoot =
        "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/"
    val epochMainRoot = adapterMainRoot + "epoch/"
    val epochTestRoot = adapterTestRoot + "epoch/"
    val contractEpochPath = contractMainRoot + "epoch/ProjectReadEpoch.kt"
    val contractNegativeTestPath = contractTestRoot + "ProjectReadEpochNegativeTest.kt"
    val contractPositiveTestPath = contractTestRoot + "ProjectReadEpochTest.kt"
    val observationPath = epochMainRoot + "ProjectReadEpochObservation.kt"
    val liveObservationPath = epochMainRoot + "LiveProjectReadEpochObservation.kt"
    val existingProjectAdmissionPath = adapterMainRoot + "ExistingProjectAdmission.kt"
    val adapterPositiveTestPath = adapterTestRoot + "EpochSignalCharacterizationTest.kt"
    val signalFixturePath = adapterTestRoot + "EpochSignalFixtures.kt"
    val signalApiContractPath = adapterTestRoot + "EpochSignalApiContract.kt"
    val signalClassContractPath = epochTestRoot + "EpochSignalClassContract.kt"
    val additionalReadEpochArtifacts = listOf(
        contractMainRoot + "epoch/SemanticReadLease.kt",
        epochMainRoot + "ProjectReadEpochIdentity.kt",
        epochMainRoot + "ProjectReadEpochVfsListener.kt",
        epochTestRoot + "ProjectReadEpochIdentityTest.kt",
        epochTestRoot + "ProjectReadEpochDetachmentTest.kt",
        epochTestRoot + "EpochSignalProductionResources.kt",
        adapterTestRoot + "ExistingProjectAdmissionFixtures.kt",
        adapterTestRoot + "ExistingProjectAdmissionNegativeTest.kt",
        adapterTestRoot + "ExistingProjectAdmissionTest.kt",
        "workspace/intellij-read/src/test/resources/KVP-017-read-epoch.expected.json",
        "docs/engineering/ide-project-read-epoch.md",
        "scripts/verify_kvp017_report.py",
    )
    val contractBuildPath = "workspace/contract/build.gradle.kts"
    val adapterBuildPath = "workspace/intellij-read/build.gradle.kts"
    val redArtifactPaths = listOf(
        "build-logic/src/main/kotlin/support/architecture/ArchitectureModel.kt",
        "build-logic/src/main/kotlin/support/architecture/JvmEffectScanner.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/JvmEffectRules.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/HostedReadForbiddenAuthority.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/HostedReadPathPolicy.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt",
        "build-logic/src/main/kotlin/support/architecture/gradle/HostedReadPathTaskFailure.kt",
        "build-logic/src/main/kotlin/support/architecture/gradle/HostedReadPathTasks.kt",
        adapterBuildPath,
    )
    val greenArtifactPaths = redArtifactPaths + listOf(
        "build-logic/src/main/kotlin/support/architecture/policy/HostedReadInventory.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/HostedReadPathReport.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/HostedReadProjectClasspath.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/HostedReadRuntimeClasspath.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/Kvp018PredecessorReceipts.kt",
        "build-logic/src/main/kotlin/support/architecture/gradle/HostedReadClassInputs.kt",
        "build-logic/src/main/kotlin/support/architecture/gradle/HostedReadExternalInputs.kt",
        "build-logic/src/main/kotlin/support/architecture/gradle/HostedReadProjectInputs.kt",
        "build-logic/src/main/kotlin/support/architecture/gradle/ArchitectureTasks.kt",
        "build-logic/src/test/kotlin/support/architecture/HostedReadClasspathFixtures.kt",
        "build-logic/src/test/kotlin/support/architecture/gradle/HostedReadInputRefinementTest.kt",
        "build-logic/src/test/kotlin/support/architecture/HostedReadPathPolicyTest.kt",
        "build-logic/src/test/kotlin/support/architecture/gradle/HostedReadPathTaskFailureTest.kt",
        "build-logic/src/test/kotlin/support/architecture/HostedReadProjectClasspathTest.kt",
        "build-logic/src/test/kotlin/support/architecture/IdeReadFirewallTest.kt",
        "build-logic/src/test/kotlin/support/architecture/JvmEffectScannerTest.kt",
        "build-logic/src/test/kotlin/support/architecture/ModuleRoleBoundaryTest.kt",
        "build-logic/src/test/kotlin/support/architecture/policy/KastCleanSlatePolicyTest.kt",
        "scripts/verify_bundle.py",
    )
    val hostedProject = project(":workspace:intellij-read")
    val hostedClasses = files(
        hostedProject.layout.buildDirectory.dir("classes/kotlin/main"),
        hostedProject.layout.buildDirectory.dir("classes/java/main"),
    )
    val hostedRequiredClasses = listOf(
        "io/github/amichne/kast/workspace/intellij/read/AdmittedIdeProject.class",
        "io/github/amichne/kast/workspace/intellij/read/LiveDetachedModelCapture.class",
        "io/github/amichne/kast/workspace/intellij/read/LiveProjectReadEpochSource.class",
        "io/github/amichne/kast/workspace/intellij/read/RootFilteredProjectEpochVfsListener.class",
    )
    val hostedRuntimeProjectFiles = files()
    val hostedRuntimeExternalFiles = files()
    hostedProject.pluginManager.withPlugin("kast.kotlin-library") {
        val runtimeArtifacts = hostedProject.configurations.getByName("runtimeClasspath").incoming
        hostedRuntimeProjectFiles.from(runtimeArtifacts.artifactView {
            componentFilter { it is ProjectComponentIdentifier }
        }.artifacts.artifactFiles)
        hostedRuntimeExternalFiles.from(runtimeArtifacts.artifactView {
            componentFilter { it !is ProjectComponentIdentifier }
        }.artifacts.artifactFiles)
    }
    val hostedRuntimeProjectResults = providers.provider {
        hostedProject.configurations.getByName("runtimeClasspath").incoming.artifactView {
            componentFilter { it is ProjectComponentIdentifier }
        }.artifacts.resolvedArtifacts.get()
            .sortedBy { artifact ->
                val component = artifact.id.componentIdentifier as ProjectComponentIdentifier
                "${component.projectPath}|${artifact.file.name}"
            }
    }
    val hostedProjectIdentitiesProvider = hostedRuntimeProjectResults.map { artifacts ->
        artifacts.map { artifact ->
            val component = artifact.id.componentIdentifier as ProjectComponentIdentifier
            "${component.projectPath}|${artifact.file.name}"
        }
    }
    val hostedRuntimeExternalResults = providers.provider {
        hostedProject.configurations.getByName("runtimeClasspath").incoming.artifactView {
            componentFilter { it !is ProjectComponentIdentifier }
        }.artifacts.resolvedArtifacts.get().sortedBy { it.file.name }
    }
    val hostedExternalIdentitiesProvider = hostedRuntimeExternalResults.map { artifacts ->
        artifacts.map { artifact ->
            val component = artifact.id.componentIdentifier
            val identity = when (component) {
                is ModuleComponentIdentifier ->
                    "${component.group}:${component.module}:${component.version}"
                else -> "UNSUPPORTED:${component.displayName}"
            }
            "$identity|${artifact.file.name}"
        }
    }

    fun Kvp018ReceiptTaskBase.configureHosted() {
        configureSignalLedger()
        readEpochTaskId.set(readEpoch.task.id.value)
        readEpochRedGateId.set(readEpoch.redGate.id)
        readEpochGreenGateId.set(readEpoch.greenGate.id)
        readEpochCompletionGateId.set(readEpoch.completionGate.id)
        readEpochRedReceiptId.set(readEpoch.redGate.outputReceiptId)
        readEpochGreenReceiptId.set(readEpoch.greenGate.outputReceiptId)
        readEpochCompletionReceiptId.set(readEpoch.completionGate.outputReceiptId)
        readEpochRedCommand.set(readEpoch.redGate.command)
        readEpochGreenCommand.set(readEpoch.greenGate.command)
        readEpochCompletionCommand.set(readEpoch.completionGate.command)
        readEpochTaskInputDigest.set(readEpoch.taskInputDigest)
        readEpochCompletionInputDigest.set(readEpoch.completionInputDigest)
        readEpochProofReportPath.set(readEpoch.task.outputs.single().path)
        readEpochContractPath.set(contractEpochPath)
        readEpochContractNegativeTestPath.set(contractNegativeTestPath)
        readEpochContractPositiveTestPath.set(contractPositiveTestPath)
        readEpochObservationPath.set(observationPath)
        readEpochLiveObservationPath.set(liveObservationPath)
        readEpochExistingProjectAdmissionPath.set(existingProjectAdmissionPath)
        readEpochAdapterPositiveTestPath.set(adapterPositiveTestPath)
        readEpochSignalFixturePath.set(signalFixturePath)
        readEpochSignalApiContractPath.set(signalApiContractPath)
        readEpochSignalClassContractPath.set(signalClassContractPath)
        readEpochAdditionalArtifactPaths.set(additionalReadEpochArtifacts)
        readEpochContractBuildPath.set(contractBuildPath)
        readEpochAdapterBuildPath.set(adapterBuildPath)
        directSignalLedgerRedReceiptFile.set(signalLedger.redReceipt)
        directSignalLedgerGreenReceiptFile.set(signalLedger.greenReceipt)
        directSignalLedgerProofReportFile.set(signalLedger.proofReport)
        directSignalLedgerCompletionReceiptFile.set(signalLedger.completionReceipt)
        readEpochContractFile.set(layout.projectDirectory.file(contractEpochPath))
        readEpochContractNegativeTestFile.set(layout.projectDirectory.file(contractNegativeTestPath))
        readEpochContractPositiveTestFile.set(layout.projectDirectory.file(contractPositiveTestPath))
        readEpochObservationFile.set(layout.projectDirectory.file(observationPath))
        readEpochLiveObservationFile.set(layout.projectDirectory.file(liveObservationPath))
        readEpochExistingProjectAdmissionFile.set(layout.projectDirectory.file(existingProjectAdmissionPath))
        readEpochAdapterPositiveTestFile.set(layout.projectDirectory.file(adapterPositiveTestPath))
        readEpochSignalFixtureFile.set(layout.projectDirectory.file(signalFixturePath))
        readEpochSignalApiContractFile.set(layout.projectDirectory.file(signalApiContractPath))
        readEpochSignalClassContractFile.set(layout.projectDirectory.file(signalClassContractPath))
        readEpochAdditionalArtifactFiles.from(additionalReadEpochArtifacts.map(layout.projectDirectory::file))
        readEpochContractBuildFile.set(layout.projectDirectory.file(contractBuildPath))
        readEpochAdapterBuildFile.set(layout.projectDirectory.file(adapterBuildPath))

        configureDetachedDependency(detached)
        dependencyDetachedArtifactFiles.from(
            KVP016_ARTIFACT_PATHS.map(layout.projectDirectory::file),
        )
        directReadEpochRedReceiptFile.set(readEpoch.redReceipt)
        directReadEpochGreenReceiptFile.set(readEpoch.greenReceipt)
        directReadEpochProofReportFile.set(readEpoch.proofReport)
        directReadEpochCompletionReceiptFile.set(readEpoch.completionReceipt)
        hostedTaskId.set(hosted.task.id.value)
        hostedRedGateId.set(hosted.redGate.id)
        hostedGreenGateId.set(hosted.greenGate.id)
        hostedCompletionGateId.set(hosted.completionGate.id)
        hostedRedReceiptId.set(hosted.redGate.outputReceiptId)
        hostedGreenReceiptId.set(hosted.greenGate.outputReceiptId)
        hostedCompletionReceiptId.set(hosted.completionGate.outputReceiptId)
        hostedRedCommand.set(hosted.redGate.command)
        hostedGreenCommand.set(hosted.greenGate.command)
        hostedCompletionCommand.set(hosted.completionGate.command)
        hostedTaskInputDigest.set(hosted.taskInputDigest)
        hostedCompletionInputDigest.set(hosted.completionInputDigest)
        hostedProofReportPath.set(hosted.task.outputs.single().path)
        hostedRedArtifactPaths.set(redArtifactPaths)
        hostedGreenArtifactPaths.set(greenArtifactPaths)
        hostedRedArtifactFiles.from(redArtifactPaths.map(layout.projectDirectory::file))
        hostedGreenArtifactFiles.from(greenArtifactPaths.map(layout.projectDirectory::file))
        hostedCompiledClassDirectories.from(hostedClasses)
        hostedRequiredClassNames.set(hostedRequiredClasses)
        hostedRuntimeProjectArtifactIdentities.set(hostedProjectIdentitiesProvider)
        hostedRuntimeProjectArtifactFiles.from(hostedRuntimeProjectFiles)
        hostedRuntimeExternalArtifactIdentities.set(hostedExternalIdentitiesProvider)
        hostedRuntimeExternalArtifactFiles.from(hostedRuntimeExternalFiles)
    }

    tasks.named("recordKVP017RedReceipt") {
        mustRunAfter("verifyKVP016CompletionReceipt")
    }
    val recordRed = tasks.register("recordKVP018RedReceipt", RecordKvp018RedReceiptTask::class.java) {
        configureHosted()
        dependsOn(
            "verifyKVP016CompletionReceipt",
            "verifyKVP017CompletionReceipt",
            ":workspace:intellij-read:classes",
        )
        receiptFile.set(hosted.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP018GreenReceipt",
        RecordKvp018GreenReceiptTask::class.java,
    ) {
        configureHosted()
        dependsOn(recordRed, ":workspace:intellij-read:classes")
        redReceiptFile.set(hosted.redReceipt)
        proofReportFile.set(hosted.proofReport)
        receiptFile.set(hosted.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP018Completion",
        DeriveKvp018CompletionReceiptTask::class.java,
    ) {
        configureHosted()
        dependsOn(recordGreen)
        redReceiptFile.set(hosted.redReceipt)
        greenReceiptFile.set(hosted.greenReceipt)
        proofReportFile.set(hosted.proofReport)
        receiptFile.set(hosted.completionReceipt)
    }
    tasks.register("verifyKVP018CompletionReceipt", VerifyKvp018CompletionReceiptTask::class.java) {
        configureHosted()
        dependsOn(derive)
        redReceiptFile.set(hosted.redReceipt)
        greenReceiptFile.set(hosted.greenReceipt)
        proofReportFile.set(hosted.proofReport)
        completionReceiptFile.set(hosted.completionReceipt)
    }
    val freshness = registerKvp019ReceiptProgression(
        program,
        readEpoch,
        hosted,
    ) {
        configureHosted()
    }
    return setOf(hosted.task.id, freshness)
}
