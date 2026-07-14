package io.github.amichne.kast.idea;

import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.ContentRootData;
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
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
        List<GradleModuleAssociation> associations = new ArrayList<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            GradleModuleData gradleModuleData = GradleModuleDataIndex.findGradleModuleData(module);
            if (gradleModuleData == null) {
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
                sourceSets(module)
            ));
        }
        associations.sort(
            Comparator.comparing(GradleModuleAssociation::ideaModuleName)
                .thenComparing(association -> association.linkedBuildRoot().toString())
                .thenComparing(GradleModuleAssociation::gradleProjectPath)
        );
        return new GradleWorkspaceModel(List.copyOf(roots), List.copyOf(associations));
    }

    private static List<GradleSourceSetAssociation> sourceSets(Module module) {
        DataNode<? extends ModuleData> moduleNode = GradleModuleDataIndex.findModuleNode(module);
        if (moduleNode == null) {
            return List.of();
        }
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

    private static void collectSourceRoots(DataNode<?> node, LinkedHashSet<Path> sourceRoots) {
        for (DataNode<?> child : node.getChildren()) {
            if (child.getKey().equals(GradleSourceSetData.KEY)) {
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
        List<GradleModuleAssociation> moduleAssociations
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
