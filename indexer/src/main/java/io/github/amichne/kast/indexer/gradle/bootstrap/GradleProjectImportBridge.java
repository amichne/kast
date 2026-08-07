package io.github.amichne.kast.indexer.gradle.bootstrap;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType;
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.observable.operation.core.ObservableOperationStatus;
import com.intellij.openapi.observable.operation.core.ObservableOperationTrace;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.platform.backend.workspace.WorkspaceModel;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.workspaceModel.ide.JpsProjectLoadingManager;
import com.intellij.workspaceModel.ide.impl.WorkspaceModelImpl;
import io.github.amichne.kast.indexer.gradle.settlement.GradleImportObservation;
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelInventory;
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelReadiness;
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelSettlementAwaiter;
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelSettlementEvidence;
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelSettlementException;
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelSettlementOutcome;
import io.github.amichne.kast.indexer.gradle.settlement.ProgressAwareFutureAwaiter;
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressAwaitException;
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressAwaitFailure;
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressAwaitOutcome;
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressDeadlineEvidence;
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressObservation;
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeWaitCompletion;
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeWaitLifecycle;
import io.github.amichne.kast.api.contract.RuntimeProgressStage;
import io.github.amichne.kast.indexer.project.IdeaIndexState;
import io.github.amichne.kast.indexer.project.ProjectLifecycleState;
import org.jetbrains.plugins.gradle.service.project.open.GradleProjectImportUtil;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.settings.GradleSystemSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;
import org.jetbrains.plugins.gradle.util.GradleImportingUtil;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class GradleProjectImportBridge {
    private static final String DISABLE_DEPENDENCY_SOURCE_DOWNLOADS =
        "-Didea.gradle.download.sources.force=false";

    private GradleProjectImportBridge() {
    }

    public static void configureIndexerApplication() {
        configureIndexerApplication(enabled -> GradleSystemSettings.getInstance().setDownloadSources(enabled));
    }

    static void configureIndexerApplication(Consumer<Boolean> updateDownloadSources) {
        updateDownloadSources.accept(false);
    }

    public static void configureIndexerImport(Project project) {
        GradleSettings settings = GradleSettings.getInstance(project);
        settings.setGradleVmOptions(withDependencySourceDownloadsDisabled(settings.getGradleVmOptions()));
    }

    static String withDependencySourceDownloadsDisabled(String currentOptions) {
        if (currentOptions != null
            && ParametersListUtil.parse(currentOptions).contains(DISABLE_DEPENDENCY_SOURCE_DOWNLOADS)) {
            return currentOptions;
        }
        if (currentOptions == null || currentOptions.isBlank()) {
            return DISABLE_DEPENDENCY_SOURCE_DOWNLOADS;
        }
        return currentOptions + " " + DISABLE_DEPENDENCY_SOURCE_DOWNLOADS;
    }

    public static boolean canLinkAndRefreshGradleProject(String externalProjectPath, Project project) {
        return hasLinkedGradleProject(project, externalProjectPath)
            || GradleProjectImportUtil.canLinkAndRefreshGradleProject(externalProjectPath, project, false);
    }

    static boolean hasLinkedGradleProject(Project project, String externalProjectPath) {
        return hasLinkedProject(
            GradleSettings.getInstance(project).getLinkedProjectsSettings(),
            externalProjectPath
        );
    }

    public static void linkAndImportGradleProject(Project project, String externalProjectPath) {
        awaitStartupActivities(project, externalProjectPath);
        if (isGradleReloadActive(project)) {
            awaitGradleModelSettlement(project);
            if (hasLinkedGradleProject(project, externalProjectPath)) {
                return;
            }
        }

        CompletableFuture<Void> importFuture = new CompletableFuture<>();
        try {
            ImportSpecBuilder importSpec = new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                .withCallback(importFuture);
            if (hasLinkedGradleProject(project, externalProjectPath)) {
                ExternalSystemUtil.refreshProject(externalProjectPath, importSpec);
            } else {
                GradleProjectSettings linkSettings =
                    GradleProjectImportUtil.createLinkSettings(Path.of(externalProjectPath), project);
                ExternalSystemUtil.linkExternalProject(linkSettings, importSpec);
            }
            awaitImport(importFuture, project);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while importing Gradle project: " + externalProjectPath, error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (isGradleReloadActive(project) || isConcurrentGradleSyncFailure(cause)) {
                awaitGradleModelSettlement(project);
                return;
            }
            throw new IllegalStateException("Gradle project import failed: " + externalProjectPath, cause);
        }
    }

    public static boolean hasLinkedProject(
        Collection<GradleProjectSettings> linkedProjects,
        String externalProjectPath
    ) {
        Path expectedPath = Path.of(externalProjectPath).toAbsolutePath().normalize();
        return linkedProjects.stream()
            .map(GradleProjectSettings::getExternalProjectPath)
            .filter(path -> path != null && !path.isBlank())
            .map(Path::of)
            .map(path -> path.toAbsolutePath().normalize())
            .anyMatch(expectedPath::equals);
    }

    public static GradleModelReadiness inspectProjectModel(Project project) {
        return DumbService.getInstance(project).runReadActionInSmartMode(() -> {
            List<Module> modules = Arrays.stream(ModuleManager.getInstance(project).getModules())
                .filter(module -> !module.isDisposed())
                .sorted(Comparator.comparing(Module::getName))
                .toList();
            List<String> moduleNames = modules.stream().map(Module::getName).toList();
            List<Module> kotlinSourceModules = modules.stream()
                .filter(GradleProjectImportBridge::hasKotlinSources)
                .toList();
            List<String> kotlinSourceModuleNames = kotlinSourceModules.stream().map(Module::getName).toList();
            List<String> compilerReadyKotlinModuleNames = kotlinSourceModules.stream()
                .filter(module -> hasUsableKotlinCompilerModel(project, module))
                .map(Module::getName)
                .toList();
            return new GradleModelReadiness(
                moduleNames,
                kotlinSourceModuleNames,
                compilerReadyKotlinModuleNames
            );
        });
    }

    private static boolean hasKotlinSources(Module module) {
        GlobalSearchScope moduleScope = GlobalSearchScope.moduleScope(module);
        FileTypeManager fileTypes = FileTypeManager.getInstance();
        FileType kotlinSource = fileTypes.getFileTypeByExtension("kt");
        FileType kotlinScript = fileTypes.getFileTypeByExtension("kts");
        return FileTypeIndex.containsFileOfType(kotlinSource, moduleScope)
            || FileTypeIndex.containsFileOfType(kotlinScript, moduleScope);
    }

    private static boolean hasUsableKotlinCompilerModel(Project project, Module module) {
        ModuleRootManager roots = ModuleRootManager.getInstance(module);
        OrderEntry[] orderEntries = roots.getOrderEntries();
        boolean everyOrderEntryResolved = Arrays.stream(orderEntries).allMatch(OrderEntry::isValid);
        GlobalSearchScope compilerScope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module);
        JavaPsiFacade javaPsi = JavaPsiFacade.getInstance(project);
        boolean jdkResolvable = javaPsi.findClass("java.nio.file.Path", compilerScope) != null;
        boolean kotlinRuntimeResolvable = javaPsi.findClass("kotlin.jvm.internal.Intrinsics", compilerScope) != null;
        return roots.getSdk() != null && everyOrderEntryResolved && jdkResolvable && kotlinRuntimeResolvable;
    }

    private static void awaitImport(CompletableFuture<Void> importFuture, Project project)
        throws InterruptedException, ExecutionException {
        requireCompleted(
            ProgressAwareFutureAwaiter.standard().await(
                RuntimeProgressStage.GRADLE_IMPORT,
                importFuture,
                () -> RuntimeProgressObservation.capture(inspectGradleImportObservation(project)),
                () -> project.isDisposed() ? RuntimeWaitLifecycle.Disposed : RuntimeWaitLifecycle.Active
            )
        );
    }

    public static void awaitGradleRefresh(Project project, CompletableFuture<Void> refreshFuture)
        throws InterruptedException, ExecutionException {
        requireCompleted(
            ProgressAwareFutureAwaiter.standard().await(
                RuntimeProgressStage.GRADLE_IMPORT,
                refreshFuture,
                () -> RuntimeProgressObservation.capture(inspectGradleImportObservation(project)),
                () -> project.isDisposed() ? RuntimeWaitLifecycle.Disposed : RuntimeWaitLifecycle.Active
            )
        );
    }

    public static GradleModelSettlementEvidence awaitGradleModelSettlement(Project project) {
        awaitStartupActivities(project, project.getBasePath() == null ? project.getName() : project.getBasePath());
        GradleModelSettlementOutcome outcome = GradleModelSettlementAwaiter
            .standard()
            .await(() -> inspectGradleImportObservation(project));
        if (outcome instanceof GradleModelSettlementOutcome.Settled settled) {
            return settled.getEvidence();
        }
        throw new GradleModelSettlementException((GradleModelSettlementOutcome.Failure) outcome);
    }

    static boolean isConcurrentGradleSyncFailure(Throwable failure) {
        String message = failure.getMessage();
        return message != null
            && message.startsWith("Another 'Sync project' task is currently running for the project:");
    }

    private static boolean isGradleReloadActive(Project project) {
        GradleImportObservation observation = inspectGradleImportObservation(project);
        return observation.getReload() != GradleReloadState.COMPLETED
            || observation.getResolve() == GradleResolveState.IN_PROGRESS;
    }

    private static GradleImportObservation inspectGradleImportObservation(Project project) {
        if (project.isDisposed()) {
            return new GradleImportObservation(
                GradleReloadState.COMPLETED,
                GradleResolveState.IDLE,
                IdeaIndexState.SMART,
                ProjectLifecycleState.DISPOSED,
                GradleModelInventory.empty()
            );
        }
        ObservableOperationTrace reload = GradleImportingUtil.getGradleProjectReloadOperation(project, project);
        ObservableOperationStatus status = reload.getStatus();
        GradleReloadState reloadState = switch (status) {
            case SCHEDULED -> GradleReloadState.SCHEDULED;
            case IN_PROGRESS -> GradleReloadState.IN_PROGRESS;
            case COMPLETED -> GradleReloadState.COMPLETED;
        };
        boolean resolveActive = ExternalSystemProcessingManager.getInstance()
            .hasTaskOfTypeInProgress(ExternalSystemTaskType.RESOLVE_PROJECT, project);
        GradleModelInventory inventory = readProjectModelInventory(() -> {
            Module[] observedModules = ModuleManager.getInstance(project).getModules();
            int sourceRootCount = Arrays.stream(observedModules)
                .filter(module -> !module.isDisposed())
                .mapToInt(module -> ModuleRootManager.getInstance(module).getSourceRoots().length)
                .sum();
            return GradleModelInventory.fromIdeaModel(observedModules.length, sourceRootCount);
        });
        return new GradleImportObservation(
            reloadState,
            resolveActive ? GradleResolveState.IN_PROGRESS : GradleResolveState.IDLE,
            DumbService.getInstance(project).isDumb() ? IdeaIndexState.DUMB : IdeaIndexState.SMART,
            ProjectLifecycleState.ACTIVE,
            inventory
        );
    }

    /**
     * Proof transition: {@code Supplier<GradleModelInventory> -> GradleModelInventory}.
     *
     * Evaluates the IDEA project-model observation under read authority. The returned inventory is
     * derived from a coherent model snapshot, so downstream settlement logic never receives module
     * or source-root primitives observed outside an IntelliJ read action.
     */
    static GradleModelInventory readProjectModelInventory(Supplier<GradleModelInventory> observation) {
        return ReadAction.compute(observation::get);
    }

    private static void awaitStartupActivities(Project project, String externalProjectPath) {
        StartupManager startup = StartupManager.getInstance(project);
        try {
            requireCompleted(
                ProgressAwareFutureAwaiter.standard().awaitCondition(
                    RuntimeProgressStage.STARTING,
                    () -> startup.postStartupActivityPassed()
                        ? RuntimeWaitCompletion.Completed
                        : RuntimeWaitCompletion.Pending,
                    () -> RuntimeProgressObservation.capture(startup.postStartupActivityPassed()),
                    () -> project.isDisposed() ? RuntimeWaitLifecycle.Disposed : RuntimeWaitLifecycle.Active
                )
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while waiting for project startup activities: " + externalProjectPath,
                error
            );
        } catch (ExecutionException error) {
            throw new IllegalStateException(
                "Project startup observation failed: " + externalProjectPath,
                error.getCause() == null ? error : error.getCause()
            );
        }
        awaitJpsProjectLoad(
            () -> workspaceModelLoadedFromCache(project),
            project::isDisposed,
            externalProjectPath,
            callback -> JpsProjectLoadingManager.getInstance(project).jpsProjectLoaded(callback)
        );
    }

    static void awaitJpsProjectLoad(
        BooleanSupplier cacheBacked,
        BooleanSupplier projectDisposed,
        String externalProjectPath,
        Consumer<Runnable> registerProjectLoadedCallback
    ) {
        if (projectDisposed.getAsBoolean()) {
            throw new IllegalStateException(
                "Project was disposed before the JPS project model loaded: " + externalProjectPath
            );
        }
        if (!cacheBacked.getAsBoolean()) {
            return;
        }
        CompletableFuture<Void> projectLoaded = new CompletableFuture<>();
        registerProjectLoadedCallback.accept(() -> projectLoaded.complete(null));
        try {
            requireCompleted(
                ProgressAwareFutureAwaiter.standard().await(
                    RuntimeProgressStage.MODEL_SETTLEMENT,
                    projectLoaded,
                    () -> RuntimeProgressObservation.capture(projectLoaded.isDone()),
                    () -> projectDisposed.getAsBoolean()
                        ? RuntimeWaitLifecycle.Disposed
                        : RuntimeWaitLifecycle.Active
                )
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while waiting for the JPS project model: " + externalProjectPath,
                error
            );
        } catch (ExecutionException error) {
            throw new IllegalStateException(
                "JPS project model load failed: " + externalProjectPath,
                error.getCause() == null ? error : error.getCause()
            );
        }
    }

    private static RuntimeProgressDeadlineEvidence requireCompleted(RuntimeProgressAwaitOutcome outcome)
        throws InterruptedException, ExecutionException {
        if (outcome instanceof RuntimeProgressAwaitOutcome.Completed completed) {
            return completed.getEvidence();
        }
        RuntimeProgressAwaitFailure failure = ((RuntimeProgressAwaitOutcome.Rejected) outcome).getFailure();
        if (failure instanceof RuntimeProgressAwaitFailure.Interrupted) {
            throw new InterruptedException("Runtime progress wait was interrupted");
        }
        if (failure instanceof RuntimeProgressAwaitFailure.FutureFailed futureFailed) {
            throw new ExecutionException(futureFailed.getCause());
        }
        if (failure instanceof RuntimeProgressAwaitFailure.FutureCancelled) {
            throw new CancellationException("Runtime progress future was cancelled");
        }
        throw new RuntimeProgressAwaitException(failure);
    }

    private static boolean workspaceModelLoadedFromCache(Project project) {
        WorkspaceModel workspaceModel = WorkspaceModel.getInstance(project);
        if (workspaceModel instanceof WorkspaceModelImpl implementation) {
            return implementation.getLoadedFromCache();
        }
        throw new IllegalStateException(
            "Unsupported IntelliJ workspace model implementation: " + workspaceModel.getClass().getName()
        );
    }

}
