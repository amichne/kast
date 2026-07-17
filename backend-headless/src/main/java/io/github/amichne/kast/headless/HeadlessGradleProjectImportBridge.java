package io.github.amichne.kast.headless;

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
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.execution.ParametersListUtil;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class HeadlessGradleProjectImportBridge {
    private static final String DISABLE_DEPENDENCY_SOURCE_DOWNLOADS =
        "-Didea.gradle.download.sources.force=false";

    private HeadlessGradleProjectImportBridge() {
    }

    public static void configureHeadlessApplication() {
        configureHeadlessApplication(enabled -> GradleSystemSettings.getInstance().setDownloadSources(enabled));
    }

    static void configureHeadlessApplication(Consumer<Boolean> updateDownloadSources) {
        updateDownloadSources.accept(false);
    }

    public static void configureHeadlessImport(Project project) {
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
        return isGradleProjectLinked(project, externalProjectPath)
            || GradleProjectImportUtil.canLinkAndRefreshGradleProject(externalProjectPath, project, false);
    }

    public static void linkAndImportGradleProject(Project project, String externalProjectPath) {
        awaitStartupActivities(project, externalProjectPath);
        if (isGradleReloadActive(project)) {
            awaitGradleModelSettlement(project);
            return;
        }

        CompletableFuture<Void> importFuture = new CompletableFuture<>();
        try {
            ImportSpecBuilder importSpec = new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                .withCallback(importFuture);
            if (isGradleProjectLinked(project, externalProjectPath)) {
                ExternalSystemUtil.refreshProject(externalProjectPath, importSpec);
            } else {
                GradleProjectSettings linkSettings =
                    GradleProjectImportUtil.createLinkSettings(Path.of(externalProjectPath), project);
                ExternalSystemUtil.linkExternalProject(linkSettings, importSpec);
            }
            awaitImport(importFuture, externalProjectPath);
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
        } catch (TimeoutException error) {
            throw new IllegalStateException("Timed out importing Gradle project: " + externalProjectPath, error);
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

    public static HeadlessGradleModelReadiness inspectProjectModel(Project project) {
        return DumbService.getInstance(project).runReadActionInSmartMode(() -> {
            List<Module> modules = Arrays.stream(ModuleManager.getInstance(project).getModules())
                .filter(module -> !module.isDisposed())
                .sorted(Comparator.comparing(Module::getName))
                .toList();
            List<String> moduleNames = modules.stream().map(Module::getName).toList();
            List<Module> kotlinSourceModules = modules.stream()
                .filter(HeadlessGradleProjectImportBridge::hasKotlinSources)
                .toList();
            List<String> kotlinSourceModuleNames = kotlinSourceModules.stream().map(Module::getName).toList();
            List<String> compilerReadyKotlinModuleNames = kotlinSourceModules.stream()
                .filter(module -> hasUsableKotlinCompilerModel(project, module))
                .map(Module::getName)
                .toList();
            return new HeadlessGradleModelReadiness(
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

    private static boolean isGradleProjectLinked(Project project, String externalProjectPath) {
        return hasLinkedProject(
            GradleSettings.getInstance(project).getLinkedProjectsSettings(),
            externalProjectPath
        );
    }

    private static void awaitImport(CompletableFuture<Void> importFuture, String externalProjectPath)
        throws InterruptedException, ExecutionException, TimeoutException {
        importFuture.get(5, TimeUnit.MINUTES);
    }

    public static HeadlessGradleModelSettlementEvidence awaitGradleModelSettlement(Project project) {
        awaitStartupActivities(project, project.getBasePath() == null ? project.getName() : project.getBasePath());
        HeadlessGradleModelSettlementOutcome outcome = HeadlessGradleModelSettlementAwaiter
            .standard()
            .await(() -> inspectGradleImportObservation(project));
        if (outcome instanceof HeadlessGradleModelSettlementOutcome.Settled settled) {
            return settled.getEvidence();
        }
        throw new HeadlessGradleModelSettlementException(outcome);
    }

    static boolean isConcurrentGradleSyncFailure(Throwable failure) {
        String message = failure.getMessage();
        return message != null
            && message.startsWith("Another 'Sync project' task is currently running for the project:");
    }

    private static boolean isGradleReloadActive(Project project) {
        HeadlessGradleImportObservation observation = inspectGradleImportObservation(project);
        return observation.getReload() != HeadlessGradleReloadState.COMPLETED
            || observation.getResolve() == HeadlessGradleResolveState.IN_PROGRESS;
    }

    private static HeadlessGradleImportObservation inspectGradleImportObservation(Project project) {
        if (project.isDisposed()) {
            return new HeadlessGradleImportObservation(
                HeadlessGradleReloadState.COMPLETED,
                HeadlessGradleResolveState.IDLE,
                HeadlessIdeaIndexState.SMART,
                HeadlessProjectLifecycleState.DISPOSED
            );
        }
        ObservableOperationTrace reload = GradleImportingUtil.getGradleProjectReloadOperation(project, project);
        ObservableOperationStatus status = reload.getStatus();
        HeadlessGradleReloadState reloadState = switch (status) {
            case SCHEDULED -> HeadlessGradleReloadState.SCHEDULED;
            case IN_PROGRESS -> HeadlessGradleReloadState.IN_PROGRESS;
            case COMPLETED -> HeadlessGradleReloadState.COMPLETED;
        };
        boolean resolveActive = ExternalSystemProcessingManager.getInstance()
            .hasTaskOfTypeInProgress(ExternalSystemTaskType.RESOLVE_PROJECT, project);
        return new HeadlessGradleImportObservation(
            reloadState,
            resolveActive ? HeadlessGradleResolveState.IN_PROGRESS : HeadlessGradleResolveState.IDLE,
            DumbService.getInstance(project).isDumb() ? HeadlessIdeaIndexState.DUMB : HeadlessIdeaIndexState.SMART,
            HeadlessProjectLifecycleState.ACTIVE
        );
    }

    private static void awaitStartupActivities(Project project, String externalProjectPath) {
        StartupManager startup = StartupManager.getInstance(project);
        long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (!startup.postStartupActivityPassed()) {
            if (project.isDisposed()) {
                throw new IllegalStateException(
                    "Project was disposed before startup activities completed: " + externalProjectPath
                );
            }
            pauseUntilNextObservation(deadlineNanos, "project startup activities for " + externalProjectPath);
        }
    }

    private static void pauseUntilNextObservation(long deadlineNanos, String operation) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new IllegalStateException("Timed out waiting for " + operation);
        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + operation, error);
        }
    }
}
