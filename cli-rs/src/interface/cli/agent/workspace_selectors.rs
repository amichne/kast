#[derive(Debug, Args, Clone)]
#[command(
    after_help = "Selector forms:\n  module:  backend:<name> | gradle:<root>#<path>\n  package: root | named:<fq-name>\n\nExamples:\n  kast agent workspace-files --workspace-root /workspace --module backend:kast.analysis-api.main --package root\n  kast agent workspace-files --workspace-root /workspace --module gradle:included/tools#:app --package named:com.example\n  kast agent workspace-files --workspace-root /workspace --kind script --fields path,module"
)]
pub struct AgentWorkspaceFilesArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Filter by `backend:<name>` or `gradle:<root>#<path>`.
    #[arg(long)]
    pub module: Option<WorkspaceModuleSelector>,
    /// Filter to one model-proven Gradle source-set name.
    #[arg(long)]
    pub source_set: Option<WorkspaceSourceSetName>,
    /// Filter Kotlin source files or Kotlin scripts.
    #[arg(long, value_enum)]
    pub kind: Option<WorkspaceFileKindFilter>,
    /// Filter package evidence with `root` or `named:<fq-name>`.
    #[arg(long = "package")]
    pub package_selector: Option<WorkspacePackageSelector>,
    /// Filter clean, dirty, or unknown Git evidence.
    #[arg(long, value_enum)]
    pub dirty: Option<WorkspaceDirtyFilter>,
    /// Filter source-index/filesystem drift evidence.
    #[arg(long, value_enum)]
    pub drift: Option<WorkspaceDriftFilter>,
    /// Filter by a normalized workspace-relative path prefix.
    #[arg(long)]
    pub path_prefix: Option<WorkspaceRelativePathPrefix>,
    /// Filter by a workspace-relative glob. Regex dialects are not accepted.
    #[arg(long)]
    pub glob: Option<WorkspaceRelativeGlob>,
    /// Maximum file records to return.
    #[arg(long, default_value_t)]
    pub limit: WorkspaceFileLimit,
    /// Opaque continuation token from a preceding workspace-file page.
    #[arg(long, conflicts_with = "count")]
    pub page_token: Option<WorkspaceFilesPublicPageToken>,
    #[command(flatten)]
    pub view: AgentWorkspaceFilesViewArgs,
}

impl AgentWorkspaceFilesArgs {
    pub(crate) fn kind_domain(&self) -> WorkspaceFileKindDomain {
        match self.kind {
            Some(WorkspaceFileKindFilter::Source) => WorkspaceFileKindDomain::SourceOnly,
            Some(WorkspaceFileKindFilter::Script) => WorkspaceFileKindDomain::ScriptOnly,
            None => WorkspaceFileKindDomain::Mixed,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WorkspaceModuleSelector {
    Backend(BackendModuleName),
    Gradle {
        build_root: WorkspaceRelativeGradleBuildRoot,
        project_path: GradleProjectPathSelector,
    },
}

impl WorkspaceModuleSelector {
    pub(crate) fn canonical(&self) -> String {
        match self {
            Self::Backend(module_name) => format!("backend:{}", module_name.as_str()),
            Self::Gradle {
                build_root,
                project_path,
            } => format!("gradle:{}#{}", build_root.as_str(), project_path.as_str()),
        }
    }
}

impl std::str::FromStr for WorkspaceModuleSelector {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if let Some(module_name) = value.strip_prefix("backend:") {
            return Ok(Self::Backend(module_name.parse()?));
        }
        if let Some(selector) = value.strip_prefix("gradle:") {
            let (build_root, project_path) = selector.split_once('#').ok_or_else(|| {
                "Gradle module selectors use `gradle:<build-root>#<project-path>`".to_string()
            })?;
            if project_path.contains('#') {
                return Err("Gradle module selectors contain exactly one `#`".to_string());
            }
            return Ok(Self::Gradle {
                build_root: build_root.parse()?,
                project_path: project_path.parse()?,
            });
        }
        Err(
            "module selectors use `backend:<exact-name>` or `gradle:<build-root>#<project-path>`"
                .to_string(),
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackendModuleName(String);

impl BackendModuleName {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for BackendModuleName {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        validate_exact_name(value, "backend module")?;
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceRelativeGradleBuildRoot(String);

impl WorkspaceRelativeGradleBuildRoot {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for WorkspaceRelativeGradleBuildRoot {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value == "." {
            return Ok(Self(value.to_string()));
        }
        let normalized = normalize_workspace_relative_path(value, "Gradle build root")?;
        Ok(Self(normalized))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GradleProjectPathSelector(String);

impl GradleProjectPathSelector {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for GradleProjectPathSelector {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if !value.starts_with(':') {
            return Err("Gradle project paths must be absolute and start with `:`".to_string());
        }
        if value != ":" && value.split(':').skip(1).any(invalid_exact_name) {
            return Err("Gradle project paths must contain non-blank project segments".to_string());
        }
        if value.contains(['/', '\\', '#']) || value.chars().any(char::is_control) {
            return Err("Gradle project paths cannot contain path separators or `#`".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceSourceSetName(String);

impl WorkspaceSourceSetName {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for WorkspaceSourceSetName {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        validate_exact_name(value, "source-set")?;
        if value.contains(['/', '\\', ':', '#']) {
            return Err("source-set names cannot contain path or owner separators".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceFileKindFilter {
    Source,
    Script,
}

impl WorkspaceFileKindFilter {
    pub(crate) fn canonical(self) -> &'static str {
        match self {
            Self::Source => "source",
            Self::Script => "script",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceFileKindDomain {
    SourceOnly,
    ScriptOnly,
    Mixed,
}

impl WorkspaceFileKindDomain {
    pub(crate) fn canonical(self) -> &'static str {
        match self {
            Self::SourceOnly => "source-only",
            Self::ScriptOnly => "script-only",
            Self::Mixed => "mixed",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WorkspacePackageSelector {
    Root,
    Named(WorkspacePackageName),
}

impl WorkspacePackageSelector {
    pub(crate) fn canonical(&self) -> String {
        match self {
            Self::Root => "root".to_string(),
            Self::Named(package_name) => {
                format!("named:{}", package_name.canonical_selector_name())
            }
        }
    }
}

impl std::str::FromStr for WorkspacePackageSelector {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value == "root" {
            return Ok(Self::Root);
        }
        let package_name = value.strip_prefix("named:").ok_or_else(|| {
            "package selectors use `root` or `named:<canonical-kotlin-package-fq-name>`".to_string()
        })?;
        Ok(Self::Named(package_name.parse()?))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspacePackageName {
    segments: Vec<String>,
}

impl WorkspacePackageName {
    pub(crate) fn semantic_fq_name(&self) -> String {
        self.segments.join(".")
    }

    fn canonical_selector_name(&self) -> String {
        self.segments
            .iter()
            .map(|segment| {
                if is_plain_kotlin_identifier(segment) {
                    segment.clone()
                } else {
                    format!("`{segment}`")
                }
            })
            .collect::<Vec<_>>()
            .join(".")
    }
}

impl std::str::FromStr for WorkspacePackageName {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let segments = canonical_kotlin_package_segments(value)?;
        Ok(Self { segments })
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceDirtyFilter {
    Clean,
    Dirty,
    Unknown,
}

impl WorkspaceDirtyFilter {
    pub(crate) fn canonical(self) -> &'static str {
        match self {
            Self::Clean => "clean",
            Self::Dirty => "dirty",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceDriftFilter {
    None,
    FilesystemOnly,
    IndexOnly,
    MissingOnDisk,
    NotApplicable,
    Unknown,
}

impl WorkspaceDriftFilter {
    pub(crate) fn canonical(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::FilesystemOnly => "filesystem-only",
            Self::IndexOnly => "index-only",
            Self::MissingOnDisk => "missing-on-disk",
            Self::NotApplicable => "not-applicable",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceRelativePathPrefix(String);

impl WorkspaceRelativePathPrefix {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for WorkspaceRelativePathPrefix {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        Ok(Self(normalize_workspace_relative_path(
            value,
            "path prefix",
        )?))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceRelativeGlob(String);

const MAX_WORKSPACE_GLOB_BYTES: usize = 512;
const MAX_WORKSPACE_GLOB_SEGMENTS: usize = 32;
const MAX_WORKSPACE_GLOB_METACHARACTERS: usize = 64;

impl WorkspaceRelativeGlob {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for WorkspaceRelativeGlob {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.starts_with("regex:") {
            return Err("workspace globs use glob syntax; `regex:` is not accepted".to_string());
        }
        if value.len() > MAX_WORKSPACE_GLOB_BYTES {
            return Err(format!(
                "workspace glob must be at most {MAX_WORKSPACE_GLOB_BYTES} bytes"
            ));
        }
        let normalized = normalize_workspace_relative_path(value, "glob")?;
        if normalized.split('/').count() > MAX_WORKSPACE_GLOB_SEGMENTS {
            return Err(format!(
                "workspace glob must contain at most {MAX_WORKSPACE_GLOB_SEGMENTS} path segments"
            ));
        }
        let metacharacters = normalized
            .chars()
            .filter(|character| matches!(character, '*' | '?' | '[' | ']'))
            .count();
        if metacharacters > MAX_WORKSPACE_GLOB_METACHARACTERS {
            return Err(format!(
                "workspace glob must contain at most {MAX_WORKSPACE_GLOB_METACHARACTERS} metacharacters"
            ));
        }
        glob::Pattern::new(&normalized)
            .map_err(|error| format!("workspace glob is invalid: {error}"))?;
        Ok(Self(normalized))
    }
}
