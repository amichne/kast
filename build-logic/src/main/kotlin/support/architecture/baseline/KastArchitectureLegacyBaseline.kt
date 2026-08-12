package support.architecture.baseline

import support.architecture.MutationDeliveryTaskId
import support.architecture.EffectObservation
import support.architecture.ForbiddenEffect
import support.architecture.JvmMember
import support.architecture.LegacyAllowance
import support.architecture.LegacyViolationKey
import support.architecture.ModuleId

internal object KastArchitectureLegacyBaseline {
    private val sourceFilesystemWrites = LegacyEffectAllowanceScope(
        ModuleId.ANALYSIS_API,
        ForbiddenEffect.SOURCE_FILESYSTEM_WRITE,
        MutationDeliveryTaskId.A06,
    ).run {
        listOf(
            allow("io/github/amichne/kast/api/io/LocalDiskFileOperations", "createTempFile", "(Ljava/lang/String;)Ljava/lang/String;", "java/nio/file/Files", "createDirectories", "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;"),
            allow("io/github/amichne/kast/api/io/LocalDiskFileOperations", "delete", "(Ljava/lang/String;)Z", "java/nio/file/Files", "deleteIfExists", "(Ljava/nio/file/Path;)Z"),
            allow("io/github/amichne/kast/api/io/LocalDiskFileOperations", "moveAtomic", "(Ljava/lang/String;Ljava/lang/String;)V", "java/nio/file/Files", "move", "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;"),
            allow("io/github/amichne/kast/api/io/LocalDiskFileOperations", "withLock", "(Ljava/lang/String;Lkotlin/jvm/functions/Function0;)Ljava/lang/Object;", "java/nio/file/Files", "createDirectories", "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;"),
            allow("io/github/amichne/kast/api/io/LocalDiskFileOperations", "writeText", "(Ljava/lang/String;Ljava/lang/String;)V", "java/nio/file/Files", "createDirectories", "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;"),
            allow("io/github/amichne/kast/api/io/LocalDiskFileOperations", "writeText", "(Ljava/lang/String;Ljava/lang/String;)V", "java/nio/file/Files", "writeString", "(Ljava/nio/file/Path;Ljava/lang/CharSequence;[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;"),
        )
    }

    private val intellijWrites = LegacyEffectAllowanceScope(
        ModuleId.INDEXER,
        ForbiddenEffect.INTELLIJ_WRITE,
        MutationDeliveryTaskId.A05,
    ).run {
        listOf(
            allow("io/github/amichne/kast/idea/backend/mutation/ExactFileImageOperationsKt\$exactFileImageCasUnderFence\$2", "invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;", "com/intellij/openapi/command/WriteCommandAction", "runWriteCommandAction", "(Lcom/intellij/openapi/project/Project;Ljava/lang/Runnable;)V"),
            allow("io/github/amichne/kast/idea/edit/IdeaEditApplier\$3", "invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;", "com/intellij/openapi/application/CoroutinesKt", "writeAction", "(Lkotlin/jvm/functions/Function0;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/edit/IdeaTextEditsKt", "applyTextEdits", "(Lio/github/amichne/kast/idea/edit/IdeaEditApplier;Lio/github/amichne/kast/api/validation/ValidatedFileEdits;Lcom/intellij/openapi/vfs/VirtualFileManager;Lcom/intellij/openapi/fileEditor/FileDocumentManager;Lcom/intellij/psi/PsiDocumentManager;Ljava/lang/String;Ljava/nio/file/Path;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "com/intellij/openapi/command/WriteCommandAction", "runWriteCommandAction", "(Lcom/intellij/openapi/project/Project;Ljava/lang/Runnable;)V"),
        )
    }

    private val gradleImportUses = LegacyEffectAllowanceScope(
        ModuleId.INDEXER,
        ForbiddenEffect.GRADLE_IMPORT,
        MutationDeliveryTaskId.F03,
    ).run {
        listOf(
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "importSpec", "(Lcom/intellij/openapi/project/Project;Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "<init>", "(Lcom/intellij/openapi/project/Project;Lcom/intellij/openapi/externalSystem/model/ProjectSystemId;)V"),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "importSpec", "(Lcom/intellij/openapi/project/Project;Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "<type>", ""),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "importSpec", "(Lcom/intellij/openapi/project/Project;Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "dontNavigateToError", "()Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;"),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "importSpec", "(Lcom/intellij/openapi/project/Project;Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "dontReportRefreshErrors", "()Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;"),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "importSpec", "(Lcom/intellij/openapi/project/Project;Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "use", "(Lcom/intellij/openapi/externalSystem/service/execution/ProgressExecutionMode;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;"),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "importSpec", "(Lcom/intellij/openapi/project/Project;Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "withActivateToolWindowOnFailure", "(Z)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;"),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "importSpec", "(Lcom/intellij/openapi/project/Project;Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "withActivateToolWindowOnStart", "(Z)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;"),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "importSpec", "(Lcom/intellij/openapi/project/Project;Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "withCallback", "(Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;"),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "importSpec", "(Lcom/intellij/openapi/project/Project;Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "withImportProjectData", "(Z)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;"),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "linkExternalGradleProject", "(Lcom/intellij/openapi/project/Project;Ljava/nio/file/Path;Ljava/util/concurrent/CompletableFuture;)V", "com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "linkExternalProject", "(Lcom/intellij/openapi/externalSystem/settings/ExternalProjectSettings;Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;)V"),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "linkExternalGradleProject", "(Lcom/intellij/openapi/project/Project;Ljava/nio/file/Path;Ljava/util/concurrent/CompletableFuture;)V", "org/jetbrains/plugins/gradle/settings/GradleProjectSettings", "<init>", "(Ljava/lang/String;)V"),
            allow("io/github/amichne/kast/idea/IdeaGradleProjectLoadBridge", "refreshExternalGradleProject", "(Lcom/intellij/openapi/project/Project;Ljava/nio/file/Path;Ljava/util/concurrent/CompletableFuture;)V", "com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "refreshProject", "(Ljava/lang/String;Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;)V"),
            allow("io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectImportBridge", "canLinkAndRefreshGradleProject", "(Ljava/lang/String;Lcom/intellij/openapi/project/Project;)Z", "org/jetbrains/plugins/gradle/service/project/open/GradleProjectImportUtil", "canLinkAndRefreshGradleProject", "(Ljava/lang/String;Lcom/intellij/openapi/project/Project;Z)Z"),
            allow("io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectImportBridge", "configureIndexerImport", "(Lcom/intellij/openapi/project/Project;)V", "org/jetbrains/plugins/gradle/settings/GradleSettings", "setGradleVmOptions", "(Ljava/lang/String;)V"),
            allow("io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectImportBridge", "lambda\$configureIndexerApplication\$0", "(Ljava/lang/Boolean;)V", "org/jetbrains/plugins/gradle/settings/GradleSystemSettings", "setDownloadSources", "(Z)V"),
            allow("io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectImportBridge", "linkAndImportGradleProject", "(Lcom/intellij/openapi/project/Project;Ljava/lang/String;)V", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "<init>", "(Lcom/intellij/openapi/project/Project;Lcom/intellij/openapi/externalSystem/model/ProjectSystemId;)V"),
            allow("io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectImportBridge", "linkAndImportGradleProject", "(Lcom/intellij/openapi/project/Project;Ljava/lang/String;)V", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "<type>", ""),
            allow("io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectImportBridge", "linkAndImportGradleProject", "(Lcom/intellij/openapi/project/Project;Ljava/lang/String;)V", "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder", "withCallback", "(Ljava/util/concurrent/CompletableFuture;)Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;"),
            allow("io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectImportBridge", "linkAndImportGradleProject", "(Lcom/intellij/openapi/project/Project;Ljava/lang/String;)V", "com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "linkExternalProject", "(Lcom/intellij/openapi/externalSystem/settings/ExternalProjectSettings;Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;)V"),
            allow("io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectImportBridge", "linkAndImportGradleProject", "(Lcom/intellij/openapi/project/Project;Ljava/lang/String;)V", "com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "refreshProject", "(Ljava/lang/String;Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;)V"),
            allow("io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectImportBridge", "linkAndImportGradleProject", "(Lcom/intellij/openapi/project/Project;Ljava/lang/String;)V", "org/jetbrains/plugins/gradle/service/project/open/GradleProjectImportUtil", "createLinkSettings", "(Ljava/nio/file/Path;Lcom/intellij/openapi/project/Project;)Lorg/jetbrains/plugins/gradle/settings/GradleProjectSettings;"),
        )
    }

    private val analysisBackendUses = LegacyEffectAllowanceScope(
        ModuleId.INDEXER,
        ForbiddenEffect.ANALYSIS_BACKEND,
        MutationDeliveryTaskId.F04,
    ).run {
        listOf(
            allow("io/github/amichne/kast/idea/IndexerServerRuntime", "startResolved-x5dyaeM", "(Lcom/intellij/openapi/project/Project;Lio/github/amichne/kast/idea/IdeaWorkspaceIdentity;Lio/github/amichne/kast/api/contract/AnalysisTransport;Lio/github/amichne/kast/api/client/KastConfig;Lio/github/amichne/kast/idea/IndexerAdmission;Lio/github/amichne/kast/idea/transition/GitWorktreeRegistrationProof;Ljava/lang/String;Lio/github/amichne/kast/indexer/gradle/bootstrap/InitialProjectModelAuthority;)Lio/github/amichne/kast/idea/RunningIndexer;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "<type>", ""),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "<class>", "", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "<type>", ""),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "<init>", "(Lio/github/amichne/kast/api/contract/CloseableAnalysisBackend;Lio/github/amichne/kast/idea/diagnostics/KastDiagnosticsService;)V", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "<type>", ""),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "applyEdits", "(Lio/github/amichne/kast/api/validation/ParsedApplyEditsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "applyEdits", "(Lio/github/amichne/kast/api/validation/ParsedApplyEditsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "callHierarchy", "(Lio/github/amichne/kast/api/validation/ParsedCallHierarchyQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "callHierarchy", "(Lio/github/amichne/kast/api/validation/ParsedCallHierarchyQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "callRelations", "(Lio/github/amichne/kast/api/contract/skill/KastCallersQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "callRelations", "(Lio/github/amichne/kast/api/contract/skill/KastCallersQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "capabilities", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "capabilities", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "close", "()V", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "close", "()V"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "codeActions", "(Lio/github/amichne/kast/api/validation/ParsedCodeActionsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "codeActions", "(Lio/github/amichne/kast/api/validation/ParsedCodeActionsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "completions", "(Lio/github/amichne/kast/api/validation/ParsedCompletionsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "completions", "(Lio/github/amichne/kast/api/validation/ParsedCompletionsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "delegate", "Lio/github/amichne/kast/api/contract/CloseableAnalysisBackend;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "<type>", ""),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "diagnostics", "(Lio/github/amichne/kast/api/validation/ParsedDiagnosticsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "diagnostics", "(Lio/github/amichne/kast/api/validation/ParsedDiagnosticsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "exactFileImageCas", "(Lio/github/amichne/kast/api/validation/ParsedExactFileImageQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "exactFileImageCas", "(Lio/github/amichne/kast/api/validation/ParsedExactFileImageQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "fileOutline", "(Lio/github/amichne/kast/api/validation/ParsedFileOutlineQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "fileOutline", "(Lio/github/amichne/kast/api/validation/ParsedFileOutlineQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "findReferences", "(Lio/github/amichne/kast/api/validation/ParsedReferencesQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "findReferences", "(Lio/github/amichne/kast/api/validation/ParsedReferencesQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "getSelectorHandles", "()Lio/github/amichne/kast/api/contract/selector/SelectorHandleAuthority;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "getSelectorHandles", "()Lio/github/amichne/kast/api/contract/selector/SelectorHandleAuthority;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "health", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "health", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "hierarchyRelations", "(Lio/github/amichne/kast/api/contract/skill/KastHierarchyQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "hierarchyRelations", "(Lio/github/amichne/kast/api/contract/skill/KastHierarchyQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "implementationRelations", "(Lio/github/amichne/kast/api/contract/skill/KastImplementationsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "implementationRelations", "(Lio/github/amichne/kast/api/contract/skill/KastImplementationsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "implementations", "(Lio/github/amichne/kast/api/validation/ParsedImplementationsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "implementations", "(Lio/github/amichne/kast/api/validation/ParsedImplementationsQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "inspectMutationScratch", "(Lio/github/amichne/kast/api/validation/ParsedMutationScratchInspectQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "inspectMutationScratch", "(Lio/github/amichne/kast/api/validation/ParsedMutationScratchInspectQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "observeExactFile", "(Lio/github/amichne/kast/api/validation/ParsedRawExactFileObservationQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "observeExactFile", "(Lio/github/amichne/kast/api/validation/ParsedRawExactFileObservationQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "optimizeImports", "(Lio/github/amichne/kast/api/validation/ParsedImportOptimizeQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "optimizeImports", "(Lio/github/amichne/kast/api/validation/ParsedImportOptimizeQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "planAddDeclaration", "(Lio/github/amichne/kast/api/validation/ParsedAddDeclarationPlanQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "planAddDeclaration", "(Lio/github/amichne/kast/api/validation/ParsedAddDeclarationPlanQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "planAddFile", "(Lio/github/amichne/kast/api/validation/ParsedAddFilePlanQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "planAddFile", "(Lio/github/amichne/kast/api/validation/ParsedAddFilePlanQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "planReplacement", "(Lio/github/amichne/kast/api/validation/ParsedReplacementPlanQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "planReplacement", "(Lio/github/amichne/kast/api/validation/ParsedReplacementPlanQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "recoverMutationScratch", "(Lio/github/amichne/kast/api/validation/ParsedMutationScratchRecoveryQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "recoverMutationScratch", "(Lio/github/amichne/kast/api/validation/ParsedMutationScratchRecoveryQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "refresh", "(Lio/github/amichne/kast/api/validation/ParsedRefreshQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "refresh", "(Lio/github/amichne/kast/api/validation/ParsedRefreshQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "rename", "(Lio/github/amichne/kast/api/validation/ParsedRenameQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "rename", "(Lio/github/amichne/kast/api/validation/ParsedRenameQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "resolveSymbol", "(Lio/github/amichne/kast/api/validation/ParsedSymbolQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "resolveSymbol", "(Lio/github/amichne/kast/api/validation/ParsedSymbolQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "runtimeStatus", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "runtimeStatus", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "semanticGraph", "(Lio/github/amichne/kast/api/validation/ParsedSemanticGraphQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "semanticGraph", "(Lio/github/amichne/kast/api/validation/ParsedSemanticGraphQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "semanticInsertionPoint", "(Lio/github/amichne/kast/api/validation/ParsedSemanticInsertionQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "semanticInsertionPoint", "(Lio/github/amichne/kast/api/validation/ParsedSemanticInsertionQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "typeHierarchy", "(Lio/github/amichne/kast/api/validation/ParsedTypeHierarchyQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "typeHierarchy", "(Lio/github/amichne/kast/api/validation/ParsedTypeHierarchyQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "verifyMutationPostcondition", "(Lio/github/amichne/kast/api/validation/ParsedMutationPostconditionQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "verifyMutationPostcondition", "(Lio/github/amichne/kast/api/validation/ParsedMutationPostconditionQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "workspaceFiles", "(Lio/github/amichne/kast/api/validation/ParsedWorkspaceFilesQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "workspaceFiles", "(Lio/github/amichne/kast/api/validation/ParsedWorkspaceFilesQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "workspaceSearch", "(Lio/github/amichne/kast/api/validation/ParsedWorkspaceSearchQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "workspaceSearch", "(Lio/github/amichne/kast/api/validation/ParsedWorkspaceSearchQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/ObservedAnalysisBackend", "workspaceSymbolSearch", "(Lio/github/amichne/kast/api/validation/ParsedWorkspaceSymbolQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "workspaceSymbolSearch", "(Lio/github/amichne/kast/api/validation/ParsedWorkspaceSymbolQuery;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/idea/RunningIndexer", "<init>", "(Lio/github/amichne/kast/api/contract/CloseableAnalysisBackend;Lio/github/amichne/kast/server/RunningAnalysisServer;Lio/github/amichne/kast/idea/KastIdeaProjectIndexing;Lio/github/amichne/kast/indexstore/store/SqliteSourceIndexStore;)V", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "<type>", ""),
            allow("io/github/amichne/kast/idea/RunningIndexer", "backend", "Lio/github/amichne/kast/api/contract/CloseableAnalysisBackend;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "<type>", ""),
            allow("io/github/amichne/kast/idea/RunningIndexer", "getBackend", "()Lio/github/amichne/kast/api/contract/CloseableAnalysisBackend;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "<type>", ""),
            allow("io/github/amichne/kast/idea/backend/KastIndexerBackend", "<class>", "", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "<type>", ""),
            allow("io/github/amichne/kast/indexer/KastIndexerRuntime\$run\$projectName\$1", "invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "runtimeStatus", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            allow("io/github/amichne/kast/indexer/KastIndexerRuntime\$start\$status\$1", "invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;", "io/github/amichne/kast/api/contract/CloseableAnalysisBackend", "runtimeStatus", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
        )
    }

    val all: List<LegacyAllowance> =
        sourceFilesystemWrites + analysisBackendUses + gradleImportUses + intellijWrites
}

private class LegacyEffectAllowanceScope(
    private val module: ModuleId,
    private val effect: ForbiddenEffect,
    private val retirementTask: MutationDeliveryTaskId,
) {
    fun allow(
        callerOwner: String,
        callerName: String,
        callerDescriptor: String,
        targetOwner: String,
        targetName: String,
        targetDescriptor: String,
    ): LegacyAllowance = LegacyAllowance(
        LegacyViolationKey.ForbiddenEffectUse(
            EffectObservation(
                module,
                effect,
                JvmMember.of(callerOwner, callerName, callerDescriptor),
                JvmMember.of(targetOwner, targetName, targetDescriptor),
            ),
        ),
        retirementTask,
    )
}
