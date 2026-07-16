package io.github.amichne.kast.idea;

import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.ContentRootData;
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
        LinkedHashSet<Path> importedSourceRoots = new LinkedHashSet<>();
        boolean[] importedModelComplete = {
            !ProjectDataManager.getInstance().getExternalProjectsData(project, GradleConstants.SYSTEM_ID).isEmpty()
        };
        for (ExternalProjectInfo projectInfo :
            ProjectDataManager.getInstance().getExternalProjectsData(project, GradleConstants.SYSTEM_ID)) {
            DataNode<?> projectStructure = projectInfo.getExternalProjectStructure();
            if (projectStructure == null || !projectStructure.isReady()) {
                importedModelComplete[0] = false;
                continue;
            }
            if (projectInfo.getLastSuccessfulImportTimestamp() <= 0 ||
                projectInfo.getLastSuccessfulImportTimestamp() < projectInfo.getLastImportTimestamp()) {
                importedModelComplete[0] = false;
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
        List<Path> sourceRoots = importedSourceRoots.stream()
            .sorted(Comparator.comparing(Path::toString))
            .toList();
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
            LinkedHashSet<Path> sourceRoots = new LinkedHashSet<>();
            collectSourceRoots(node, sourceRoots);
            sourceSets.add(new GradleSourceSetAssociation(
                sourceSetName,
                sourceRoots.stream().sorted(Comparator.comparing(Path::toString)).toList()
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

    private static void collectModuleSourceRoots(DataNode<?> node, LinkedHashSet<Path> sourceRoots) {
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
                        .forEach(sourceRoots::add);
                }
            }
            collectModuleSourceRoots(child, sourceRoots);
        }
    }

    private static void collectSourceRoots(DataNode<?> node, LinkedHashSet<Path> sourceRoots) {
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
                        .forEach(sourceRoots::add);
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

    private static String normalizePath(Path path) {
        return normalize(path).toString();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    public record GradleWorkspaceModel(
        List<Path> linkedBuildRoots,
        boolean importedModelComplete,
        List<GradleModuleIdentity> importedModuleIdentities,
        List<LoadedGradleModule> loadedModules,
        List<Path> importedSourceRoots,
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
        List<Path> sourceRoots
    ) {
    }
}
