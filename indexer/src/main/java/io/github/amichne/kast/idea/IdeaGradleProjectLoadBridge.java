package io.github.amichne.kast.idea;

import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTask;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskState;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType;
import com.intellij.openapi.externalSystem.model.project.ContentRootData;
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager;
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager;
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import org.jetbrains.plugins.gradle.model.data.BuildParticipant;
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData;
import org.jetbrains.plugins.gradle.service.project.GradleModuleDataIndex;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.util.GradleModuleData;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

public final class IdeaGradleProjectLoadBridge {
    private IdeaGradleProjectLoadBridge() {
    }

    public static boolean isExternalGradleProjectLinked(Project project, Path externalProjectPath) {
        String normalizedExternalProjectPath = normalizePath(externalProjectPath);
        return GradleSettings.getInstance(project).getLinkedProjectsSettings().stream()
            .map(GradleProjectSettings::getExternalProjectPath)
            .filter(Objects::nonNull)
            .map(Path::of)
            .map(IdeaGradleProjectLoadBridge::normalizePath)
            .anyMatch(normalizedExternalProjectPath::equals);
    }

    public static boolean isExternalGradleProjectModelComplete(Project project, Path externalProjectPath) {
        String normalizedExternalProjectPath = normalizePath(externalProjectPath);
        return ProjectDataManager.getInstance()
            .getExternalProjectsData(project, GradleConstants.SYSTEM_ID)
            .stream()
            .filter(projectInfo -> {
                String projectPath = projectInfo.getExternalProjectPath();
                return projectPath != null &&
                    normalizedExternalProjectPath.equals(normalizePath(Path.of(projectPath)));
            })
            .anyMatch(IdeaGradleProjectLoadBridge::isImportedModelComplete);
    }

    /**
     * Joins IDEA's exact-project Gradle import when it already owns one. The caller
     * can issue a refresh when this returns false.
     */
    public static boolean awaitExternalGradleProjectImport(
        Project project,
        Path externalProjectPath,
        CompletableFuture<Void> importFuture
    ) {
        ExternalSystemTask task = ExternalSystemProcessingManager.getInstance().findTask(
            ExternalSystemTaskType.RESOLVE_PROJECT,
            GradleConstants.SYSTEM_ID,
            normalizePath(externalProjectPath)
        );
        if (task == null || task.getState().isStopped()) {
            return false;
        }

        ExternalSystemProgressNotificationManager notifications =
            ExternalSystemProgressNotificationManager.getInstance();
        ExternalSystemTaskNotificationListener listener = new ExternalSystemTaskNotificationListener() {
            @Override
            public void onSuccess(String projectPath, ExternalSystemTaskId id) {
                importFuture.complete(null);
            }

            @Override
            public void onFailure(String projectPath, ExternalSystemTaskId id, Exception error) {
                importFuture.completeExceptionally(error);
            }

            @Override
            public void onCancel(String projectPath, ExternalSystemTaskId id) {
                importFuture.completeExceptionally(
                    new CancellationException("Gradle project import was canceled")
                );
            }

            @Override
            public void onEnd(String projectPath, ExternalSystemTaskId id) {
                completeFromTaskState(task, importFuture);
            }
        };
        if (!notifications.addNotificationListener(task.getId(), listener)) {
            return false;
        }
        importFuture.whenComplete((ignored, error) ->
            notifications.removeNotificationListener(listener)
        );
        task.refreshState();
        completeFromTaskState(task, importFuture);
        return true;
    }

    /**
     * Reads only the Gradle model identities needed by workspace inventory. Kotlin owns
     * canonical workspace admission and file candidate collection; this bridge keeps
     * unstable Gradle plugin classes out of that implementation.
     */
    public static GradleWorkspaceModel readWorkspaceModel(Project project) {
        LinkedHashSet<Path> linkedBuildRoots = new LinkedHashSet<>();
        for (GradleProjectSettings settings : GradleSettings.getInstance(project).getLinkedProjectsSettings()) {
            String externalProjectPath = settings.getExternalProjectPath();
            if (externalProjectPath != null) {
                linkedBuildRoots.add(normalize(Path.of(externalProjectPath)));
            }
            GradleProjectSettings.CompositeBuild compositeBuild = settings.getCompositeBuild();
            if (compositeBuild == null) {
                continue;
            }
            for (BuildParticipant participant : compositeBuild.getCompositeParticipants()) {
                String rootPath = participant.getRootPath();
                if (rootPath != null) {
                    linkedBuildRoots.add(normalize(Path.of(rootPath)));
                }
            }
        }

        List<Path> roots = linkedBuildRoots.stream()
            .sorted(Comparator.comparing(Path::toString))
            .toList();
        LinkedHashSet<GradleModuleIdentity> importedModuleIdentities = new LinkedHashSet<>();
        Map<Path, LinkedHashSet<ExternalSystemSourceType>> importedSourceRoots = new LinkedHashMap<>();
        boolean[] importedModelComplete = {
            !ProjectDataManager.getInstance().getExternalProjectsData(project, GradleConstants.SYSTEM_ID).isEmpty()
        };
        for (ExternalProjectInfo projectInfo :
            ProjectDataManager.getInstance().getExternalProjectsData(project, GradleConstants.SYSTEM_ID)) {
            if (!isImportedModelComplete(projectInfo)) {
                importedModelComplete[0] = false;
            }
            DataNode<?> projectStructure = projectInfo.getExternalProjectStructure();
            if (projectStructure == null || !projectStructure.isReady()) {
                continue;
            }
            projectStructure.visit(node -> {
                if (!(node.getData() instanceof ModuleData moduleData)) {
                    return;
                }
                GradleModuleIdentity identity = moduleIdentity(moduleData);
                if (identity == null) {
                    importedModelComplete[0] = false;
                } else {
                    importedModuleIdentities.add(identity);
                }
                collectModuleSourceRoots(node, importedSourceRoots);
            });
        }

        List<LoadedGradleModule> loadedModules = new ArrayList<>();
        List<GradleModuleAssociation> associations = new ArrayList<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            if (module.isDisposed()) {
                continue;
            }
            DataNode<? extends ModuleData> moduleNode = GradleModuleDataIndex.findModuleNode(module);
            if (moduleNode != null) {
                GradleModuleIdentity identity = moduleIdentity(moduleNode.getData());
                if (identity == null) {
                    importedModelComplete[0] = false;
                } else {
                    loadedModules.add(new LoadedGradleModule(
                        module.getName(),
                        identity
                    ));
                }
            }
            GradleModuleData gradleModuleData = GradleModuleDataIndex.findGradleModuleData(module);
            if (gradleModuleData == null || moduleNode == null) {
                continue;
            }
            String gradleProjectDirectory = gradleModuleData.getGradleProjectDir();
            String linkedExternalProjectPath = gradleModuleData.getModuleData().getLinkedExternalProjectPath();
            String gradleProjectPath = gradleModuleData.getGradlePathOrNull();
            if (gradleProjectDirectory == null || linkedExternalProjectPath == null || gradleProjectPath == null) {
                continue;
            }
            Path projectDirectory = normalize(Path.of(gradleProjectDirectory));
            Path externalProjectDirectory = normalize(Path.of(linkedExternalProjectPath));
            Path linkedBuildRoot = roots.stream()
                .filter(root -> projectDirectory.startsWith(root) || externalProjectDirectory.startsWith(root))
                .max(Comparator.comparingInt(root -> root.getNameCount()))
                .orElse(null);
            if (linkedBuildRoot == null) {
                continue;
            }
            associations.add(new GradleModuleAssociation(
                module.getName(),
                linkedBuildRoot,
                projectDirectory,
                gradleProjectPath,
                gradleProjectPath.equals(":") && projectDirectory.equals(linkedBuildRoot),
                gradleModuleData.isIncludedBuild(),
                sourceSets(moduleNode)
            ));
        }
        loadedModules.sort(
            Comparator.comparing(LoadedGradleModule::ideaModuleName)
                .thenComparing(module -> module.identity().externalProjectPath().toString())
                .thenComparing(module -> module.identity().externalModuleId())
        );
        associations.sort(
            Comparator.comparing(GradleModuleAssociation::ideaModuleName)
                .thenComparing(association -> association.linkedBuildRoot().toString())
                .thenComparing(GradleModuleAssociation::gradleProjectPath)
        );
        List<GradleModuleIdentity> importedModules = importedModuleIdentities.stream()
            .sorted(
                Comparator.comparing((GradleModuleIdentity identity) -> identity.externalProjectPath().toString())
                    .thenComparing(GradleModuleIdentity::externalModuleId)
            )
            .toList();
        List<GradleSourceRoot> sourceRoots = classifySourceRoots(importedSourceRoots);
        return new GradleWorkspaceModel(
            List.copyOf(roots),
            importedModelComplete[0],
            importedModules,
            List.copyOf(loadedModules),
            sourceRoots,
            List.copyOf(associations)
        );
    }

    private static List<GradleSourceSetAssociation> sourceSets(DataNode<? extends ModuleData> moduleNode) {
        List<GradleSourceSetAssociation> sourceSets = new ArrayList<>();
        moduleNode.visit(node -> {
            if (!(node.getData() instanceof GradleSourceSetData sourceSetData)) {
                return;
            }
            String externalName = sourceSetData.getExternalName();
            int separator = externalName.lastIndexOf(':');
            String sourceSetName = separator >= 0 ? externalName.substring(separator + 1) : externalName;
            Map<Path, LinkedHashSet<ExternalSystemSourceType>> sourceRoots = new LinkedHashMap<>();
            collectSourceRoots(node, sourceRoots);
            sourceSets.add(new GradleSourceSetAssociation(
                sourceSetName,
                classifySourceRoots(sourceRoots)
            ));
        });
        return sourceSets.stream()
            .distinct()
            .sorted(Comparator.comparing(GradleSourceSetAssociation::sourceSetName))
            .toList();
    }

    private static GradleModuleIdentity moduleIdentity(ModuleData moduleData) {
        String externalProjectPath = moduleData.getLinkedExternalProjectPath();
        String externalModuleId = moduleData.getId();
        if (externalProjectPath == null || externalProjectPath.isBlank() ||
            externalModuleId == null || externalModuleId.isBlank()) {
            return null;
        }
        return new GradleModuleIdentity(normalize(Path.of(externalProjectPath)), externalModuleId);
    }

    private static void collectModuleSourceRoots(
        DataNode<?> node,
        Map<Path, LinkedHashSet<ExternalSystemSourceType>> sourceRoots
    ) {
        for (DataNode<?> child : node.getChildren()) {
            if (child.getData() instanceof ModuleData) {
                continue;
            }
            if (child.getKey().equals(ProjectKeys.CONTENT_ROOT) && child.getData() instanceof ContentRootData contentRoot) {
                for (ExternalSystemSourceType sourceType : ExternalSystemSourceType.values()) {
                    if (sourceType.isExcluded() || sourceType.isResource()) {
                        continue;
                    }
                    contentRoot.getPaths(sourceType).stream()
                        .map(ContentRootData.SourceRoot::getPath)
                        .map(Path::of)
                        .map(IdeaGradleProjectLoadBridge::normalize)
                        .forEach(path -> sourceRoots
                            .computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                            .add(sourceType));
                }
            }
            collectModuleSourceRoots(child, sourceRoots);
        }
    }

    private static void collectSourceRoots(
        DataNode<?> node,
        Map<Path, LinkedHashSet<ExternalSystemSourceType>> sourceRoots
    ) {
        for (DataNode<?> child : node.getChildren()) {
            if (child.getData() instanceof ModuleData) {
                continue;
            }
            if (child.getKey().equals(ProjectKeys.CONTENT_ROOT) && child.getData() instanceof ContentRootData contentRoot) {
                for (ExternalSystemSourceType sourceType : ExternalSystemSourceType.values()) {
                    if (sourceType.isExcluded() || sourceType.isResource()) {
                        continue;
                    }
                    contentRoot.getPaths(sourceType).stream()
                        .map(ContentRootData.SourceRoot::getPath)
                        .map(Path::of)
                        .map(IdeaGradleProjectLoadBridge::normalize)
                        .forEach(path -> sourceRoots
                            .computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                            .add(sourceType));
                }
            }
            collectSourceRoots(child, sourceRoots);
        }
    }

    public static void linkExternalGradleProject(
        Project project,
        Path externalProjectPath,
        CompletableFuture<Void> importFuture
    ) {
        GradleProjectSettings linkSettings =
            new GradleProjectSettings(normalizePath(externalProjectPath));
        ImportSpecBuilder importSpec = importSpec(project, importFuture);
        ExternalSystemUtil.linkExternalProject(linkSettings, importSpec);
    }

    public static void refreshExternalGradleProject(
        Project project,
        Path externalProjectPath,
        CompletableFuture<Void> importFuture
    ) {
        ExternalSystemUtil.refreshProject(
            externalProjectPath.toAbsolutePath().normalize().toString(),
            importSpec(project, importFuture)
        );
    }

    private static ImportSpecBuilder importSpec(Project project, CompletableFuture<Void> importFuture) {
        return new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
            .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
            .withImportProjectData(true)
            .withActivateToolWindowOnStart(false)
            .withActivateToolWindowOnFailure(false)
            .dontNavigateToError()
            .dontReportRefreshErrors()
            .withCallback(importFuture);
    }

    private static boolean isImportedModelComplete(ExternalProjectInfo projectInfo) {
        DataNode<?> structure = projectInfo.getExternalProjectStructure();
        return structure != null &&
            structure.isReady() &&
            projectInfo.getLastSuccessfulImportTimestamp() > 0 &&
            projectInfo.getLastSuccessfulImportTimestamp() >= projectInfo.getLastImportTimestamp();
    }

    private static void completeFromTaskState(
        ExternalSystemTask task,
        CompletableFuture<Void> importFuture
    ) {
        ExternalSystemTaskState state = task.getState();
        if (state == ExternalSystemTaskState.FINISHED) {
            importFuture.complete(null);
        } else if (state == ExternalSystemTaskState.FAILED) {
            Throwable error = task.getError();
            importFuture.completeExceptionally(
                error == null ? new IllegalStateException("Gradle project import failed") : error
            );
        } else if (state == ExternalSystemTaskState.CANCELED) {
            importFuture.completeExceptionally(
                new CancellationException("Gradle project import was canceled")
            );
        } else if (state.isStopped()) {
            importFuture.completeExceptionally(
                new IllegalStateException("Gradle project import stopped in state " + state)
            );
        }
    }

    private static String normalizePath(Path path) {
        return normalize(path).toString();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static List<GradleSourceRoot> classifySourceRoots(
        Map<Path, ? extends Collection<ExternalSystemSourceType>> sourceRoots
    ) {
        return sourceRoots.entrySet().stream()
            .map(entry -> classifySourceRoot(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(sourceRoot -> sourceRoot.path().toString()))
            .toList();
    }

    static GradleSourceRoot classifySourceRoot(
        Path path,
        Collection<ExternalSystemSourceType> sourceTypes
    ) {
        Objects.requireNonNull(sourceTypes, "sourceTypes");
        List<ExternalSystemSourceType> exactSourceTypes = sourceTypes.stream()
            .map(sourceType -> Objects.requireNonNull(sourceType, "sourceType"))
            .distinct()
            .sorted(Comparator.comparing(Enum::name))
            .toList();
        List<GradleSourceRootModelEvidence> modelEvidence = exactSourceTypes.stream()
            .map(IdeaGradleProjectLoadBridge::modelEvidence)
            .toList();
        GradleSourceRootProvenance provenance;
        if (exactSourceTypes.isEmpty()) {
            provenance = new GradleSourceRootProvenance.Unknown(
                "Gradle model supplied no source-type classification",
                modelEvidence
            );
        } else if (exactSourceTypes.stream().anyMatch(sourceType -> sourceType.isExcluded() || sourceType.isResource())) {
            provenance = new GradleSourceRootProvenance.Unknown(
                "Gradle model supplied a non-code source-type classification",
                modelEvidence
            );
        } else if (exactSourceTypes.stream().allMatch(ExternalSystemSourceType::isGenerated)) {
            provenance = new GradleSourceRootProvenance.Generated(modelEvidence);
        } else if (exactSourceTypes.stream().noneMatch(ExternalSystemSourceType::isGenerated)) {
            provenance = new GradleSourceRootProvenance.Authored(modelEvidence);
        } else {
            provenance = new GradleSourceRootProvenance.Unknown(
                "Gradle model supplied conflicting authored and generated classifications",
                modelEvidence
            );
        }
        return new GradleSourceRoot(path, provenance);
    }

    private static GradleSourceRootModelEvidence modelEvidence(ExternalSystemSourceType sourceType) {
        return switch (sourceType) {
            case SOURCE -> GradleSourceRootModelEvidence.SOURCE;
            case TEST -> GradleSourceRootModelEvidence.TEST;
            case EXCLUDED -> GradleSourceRootModelEvidence.EXCLUDED;
            case SOURCE_GENERATED -> GradleSourceRootModelEvidence.SOURCE_GENERATED;
            case TEST_GENERATED -> GradleSourceRootModelEvidence.TEST_GENERATED;
            case RESOURCE -> GradleSourceRootModelEvidence.RESOURCE;
            case TEST_RESOURCE -> GradleSourceRootModelEvidence.TEST_RESOURCE;
            case RESOURCE_GENERATED -> GradleSourceRootModelEvidence.RESOURCE_GENERATED;
            case TEST_RESOURCE_GENERATED -> GradleSourceRootModelEvidence.TEST_RESOURCE_GENERATED;
        };
    }

    public record GradleWorkspaceModel(
        List<Path> linkedBuildRoots,
        boolean importedModelComplete,
        List<GradleModuleIdentity> importedModuleIdentities,
        List<LoadedGradleModule> loadedModules,
        List<GradleSourceRoot> importedSourceRoots,
        List<GradleModuleAssociation> moduleAssociations
    ) {
    }

    public record GradleModuleIdentity(
        Path externalProjectPath,
        String externalModuleId
    ) {
    }

    public record LoadedGradleModule(
        String ideaModuleName,
        GradleModuleIdentity identity
    ) {
    }

    public record GradleModuleAssociation(
        String ideaModuleName,
        Path linkedBuildRoot,
        Path gradleProjectDirectory,
        String gradleProjectPath,
        boolean rootModule,
        boolean includedBuild,
        List<GradleSourceSetAssociation> sourceSets
    ) {
    }

    public record GradleSourceSetAssociation(
        String sourceSetName,
        List<GradleSourceRoot> sourceRoots
    ) {
    }

    public record GradleSourceRoot(
        Path path,
        GradleSourceRootProvenance provenance
    ) {
        public GradleSourceRoot {
            path = normalize(Objects.requireNonNull(path, "path"));
            provenance = Objects.requireNonNull(provenance, "provenance");
        }

        public String stableIdentity() {
            return path.toString().replace('\\', '/') + '|' + provenance.stableIdentity();
        }
    }

    public enum GradleSourceRootModelEvidence {
        SOURCE(true, false),
        TEST(true, false),
        EXCLUDED(false, false),
        SOURCE_GENERATED(true, true),
        TEST_GENERATED(true, true),
        RESOURCE(false, false),
        TEST_RESOURCE(false, false),
        RESOURCE_GENERATED(false, true),
        TEST_RESOURCE_GENERATED(false, true);

        private final boolean code;
        private final boolean generated;

        GradleSourceRootModelEvidence(boolean code, boolean generated) {
            this.code = code;
            this.generated = generated;
        }

        private boolean isAuthoredCode() {
            return code && !generated;
        }

        private boolean isGeneratedCode() {
            return code && generated;
        }
    }

    public sealed interface GradleSourceRootProvenance
        permits GradleSourceRootProvenance.Authored,
            GradleSourceRootProvenance.Generated,
            GradleSourceRootProvenance.Unknown {
        List<GradleSourceRootModelEvidence> modelEvidence();

        String stableIdentity();

        record Authored(List<GradleSourceRootModelEvidence> modelEvidence)
            implements GradleSourceRootProvenance {
            public Authored {
                modelEvidence = requiredModelEvidence(modelEvidence);
                if (modelEvidence.stream().anyMatch(evidence -> !evidence.isAuthoredCode())) {
                    throw new IllegalArgumentException("Authored provenance requires authored Gradle code evidence");
                }
            }

            @Override
            public String stableIdentity() {
                return "AUTHORED:" + evidenceIdentity(modelEvidence);
            }
        }

        record Generated(List<GradleSourceRootModelEvidence> modelEvidence)
            implements GradleSourceRootProvenance {
            public Generated {
                modelEvidence = requiredModelEvidence(modelEvidence);
                if (modelEvidence.stream().anyMatch(evidence -> !evidence.isGeneratedCode())) {
                    throw new IllegalArgumentException("Generated provenance requires generated Gradle code evidence");
                }
            }

            @Override
            public String stableIdentity() {
                return "GENERATED:" + evidenceIdentity(modelEvidence);
            }
        }

        record Unknown(String reason, List<GradleSourceRootModelEvidence> modelEvidence)
            implements GradleSourceRootProvenance {
            public Unknown {
                reason = Objects.requireNonNull(reason, "reason").trim();
                if (reason.isEmpty()) {
                    throw new IllegalArgumentException("Unknown Gradle source-root provenance requires a reason");
                }
                modelEvidence = copyModelEvidence(modelEvidence);
            }

            @Override
            public String stableIdentity() {
                return "UNKNOWN:" + reason + ':' + evidenceIdentity(modelEvidence);
            }
        }
    }

    private static List<GradleSourceRootModelEvidence> requiredModelEvidence(
        List<GradleSourceRootModelEvidence> modelEvidence
    ) {
        List<GradleSourceRootModelEvidence> exactEvidence = copyModelEvidence(modelEvidence);
        if (exactEvidence.isEmpty()) {
            throw new IllegalArgumentException("Known Gradle source-root provenance requires model evidence");
        }
        return exactEvidence;
    }

    private static List<GradleSourceRootModelEvidence> copyModelEvidence(
        List<GradleSourceRootModelEvidence> modelEvidence
    ) {
        Objects.requireNonNull(modelEvidence, "modelEvidence");
        return modelEvidence.stream()
            .map(evidence -> Objects.requireNonNull(evidence, "modelEvidence entry"))
            .distinct()
            .sorted(Comparator.comparing(Enum::name))
            .toList();
    }

    private static String evidenceIdentity(List<GradleSourceRootModelEvidence> modelEvidence) {
        return modelEvidence.stream()
            .map(Enum::name)
            .reduce((left, right) -> left + ',' + right)
            .orElse("");
    }
}
