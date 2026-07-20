#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct AgentArgs {
    #[command(subcommand)]
    pub command: Option<AgentCommand>,
}

#[derive(Debug, Subcommand, Clone)]
pub enum AgentCommand {
    /// Run the Language Server Protocol adapter over stdio.
    Lsp(LspArgs),
    /// Acquire, inspect, or release an exact-root semantic workspace lease.
    Lease(AgentLeaseArgs),
    /// Begin, inspect, finish, or abort one deterministic agent task.
    Task(AgentTaskArgs),
    /// Verify backend health, runtime state, and capabilities.
    Verify(AgentVerifyArgs),
    /// Discover Kotlin source and script files with typed workspace evidence.
    WorkspaceFiles(AgentWorkspaceFilesArgs),
    /// Query and resolve a symbol identity.
    Symbol(AgentSymbolArgs),
    /// Find bounded references to one compiler-anchored declaration.
    References(AgentReferencesArgs),
    /// Find bounded incoming callers of one compiler-anchored function.
    Callers(AgentCallsArgs),
    /// Find bounded outgoing callees of one compiler-anchored function.
    Callees(AgentCallsArgs),
    /// Find bounded implementations of one compiler-anchored type.
    Implementations(AgentImplementationsArgs),
    /// Navigate a bounded type hierarchy from one compiler-anchored type.
    Hierarchy(AgentHierarchyArgs),
    /// Query source-index impact for a fully-qualified symbol.
    Impact(AgentImpactArgs),
    /// Refresh touched files and run diagnostics.
    Diagnostics(AgentDiagnosticsArgs),
    /// Rename a compiler-resolved symbol by identity.
    Rename(AgentRenameArgs),
    /// Create a new Kotlin file from content.
    AddFile(AgentAddFileArgs),
    /// Add a declaration inside a file or named scope.
    AddDeclaration(AgentScopedMutationArgs),
    /// Add implementation content inside a file or named scope.
    AddImplementation(AgentScopedMutationArgs),
    /// Add a statement inside a named executable scope.
    AddStatement(AgentStatementMutationArgs),
    /// Replace a named declaration by symbol identity.
    ReplaceDeclaration(AgentReplaceDeclarationArgs),
    /// List catalog-backed tools for CLI-capable agent hosts.
    #[command(hide = true)]
    Tools(RemovedAgentCommandArgs),
    /// Call any catalog method with params from flags, file, or stdin.
    #[command(hide = true)]
    Call(RemovedAgentCommandArgs),
    /// Run a file-backed multi-step workflow.
    #[command(hide = true)]
    Workflow(RemovedAgentCommandArgs),
}

#[derive(Debug, Args, Clone, Default)]
pub struct AgentGuidanceSetupArgs {
    /// Absolute workspace root for harness-agnostic agent resource setup.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Packaged skill target root. Defaults to configured setup, then .agents/skills.
    #[arg(long = "skill-target-dir")]
    pub skill_target_dir: Option<PathBuf>,
    /// Repository context file to patch with Kast managed guidance.
    #[arg(long = "context-file")]
    pub context_files: Vec<PathBuf>,
    /// Overwrite modified Kast managed regions.
    #[arg(short = 'f', long)]
    pub force: bool,
    /// Do not add the managed skill path to Git info/exclude.
    #[arg(long)]
    pub no_auto_exclude_git: bool,
    /// Explain setup without writing files.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Args, Clone, Default)]
pub struct AgentRuntimeArgs {
    /// Absolute workspace root for daemon lifecycle and RPC commands.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Pin the command to a specific backend.
    #[arg(long = "backend", value_enum)]
    pub backend_name: Option<BackendName>,
    /// Opaque workspace lease acquired for this exact root and backend.
    #[arg(long = "lease-id")]
    pub lease_id: Option<AgentWorkspaceLeaseId>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentWorkspaceLeaseId(String);

impl AgentWorkspaceLeaseId {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for AgentWorkspaceLeaseId {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.is_empty()
            || value.trim() != value
            || value.chars().any(char::is_whitespace)
            || value.chars().any(char::is_control)
        {
            return Err("workspace lease ids must be non-blank opaque tokens".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Args, Clone)]
pub struct AgentLeaseArgs {
    #[command(subcommand)]
    pub command: AgentLeaseCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum AgentLeaseCommand {
    /// Acquire a READY lease for one exact semantic workspace root.
    Acquire(AgentLeaseAcquireArgs),
    /// Inspect a lease without changing runtime ownership.
    Status(AgentLeaseAccessArgs),
    /// Release a lease and stop only the exact runtime it started.
    Release(AgentLeaseAccessArgs),
}

#[derive(Debug, Args, Clone)]
pub struct AgentTaskArgs {
    #[command(subcommand)]
    pub command: AgentTaskCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum AgentTaskCommand {
    /// Begin or join the shared task for one exact workspace root.
    Begin(AgentTaskWorkspaceArgs),
    /// Inspect current task proof and blockers without changing the receipt.
    Status(AgentTaskWorkspaceArgs),
    /// Validate relevant changes and complete only with current proof.
    Finish(AgentTaskWorkspaceArgs),
    /// Repair interrupted coordination without changing workspace files.
    Repair(AgentTaskWorkspaceArgs),
    /// Close the shared task without claiming completion.
    Abort(AgentTaskWorkspaceArgs),
}

#[derive(Debug, Args, Clone, Default)]
pub struct AgentTaskWorkspaceArgs {
    /// Exact workspace root. Defaults to the nearest Gradle workspace from the current directory.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentLeaseAcquireArgs {
    /// Absolute semantic workspace root to bind.
    #[arg(long)]
    pub workspace_root: PathBuf,
    /// Maximum time to settle the runtime to READY.
    #[arg(long, default_value_t = crate::cli::DEFAULT_RUNTIME_WAIT_TIMEOUT_MS, hide = true)]
    pub wait_timeout_ms: u64,
}

#[derive(Debug, Args, Clone)]
pub struct AgentLeaseAccessArgs {
    /// Opaque lease identifier returned by `lease acquire`.
    #[arg(long = "lease-id")]
    pub lease_id: AgentWorkspaceLeaseId,
    /// Absolute semantic workspace root the lease must bind.
    #[arg(long)]
    pub workspace_root: PathBuf,
}

#[derive(Debug, Args, Clone, Default)]
pub struct RemovedAgentCommandArgs {
    /// Raw stale command arguments preserved only so removed commands can emit a tombstone.
    #[arg(allow_hyphen_values = true, trailing_var_arg = true)]
    pub args: Vec<String>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentVerifyArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub view: AgentVerifyViewArgs,
}

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WorkspaceFileLimit(std::num::NonZeroU8);

impl WorkspaceFileLimit {
    pub(crate) fn get(self) -> u8 {
        self.0.get()
    }
}

impl Default for WorkspaceFileLimit {
    fn default() -> Self {
        Self(std::num::NonZeroU8::new(20).expect("workspace-file default limit is positive"))
    }
}

impl std::fmt::Display for WorkspaceFileLimit {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(formatter)
    }
}

impl std::str::FromStr for WorkspaceFileLimit {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let value = value.parse::<u16>().map_err(|_| {
            "workspace-file limit must be an integer from 1 through 200".to_string()
        })?;
        if !(1..=200).contains(&value) {
            return Err("workspace-file limit must be from 1 through 200".to_string());
        }
        let value = u8::try_from(value)
            .map_err(|_| "workspace-file limit exceeded its typed range".to_string())?;
        Ok(Self(std::num::NonZeroU8::new(value).ok_or_else(|| {
            "workspace-file limit must be greater than 0".to_string()
        })?))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceFilesPublicPageToken(uuid::Uuid);

impl WorkspaceFilesPublicPageToken {
    pub(crate) fn canonical(&self) -> String {
        self.0.hyphenated().to_string()
    }
}

impl std::str::FromStr for WorkspaceFilesPublicPageToken {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let parsed = uuid::Uuid::parse_str(value)
            .map_err(|_| "workspace-file page token must be a canonical UUID v4".to_string())?;
        if parsed.get_version() != Some(uuid::Version::Random)
            || parsed.hyphenated().to_string() != value
        {
            return Err("workspace-file page token must be a canonical UUID v4".to_string());
        }
        Ok(Self(parsed))
    }
}

fn validate_exact_name(value: &str, label: &str) -> Result<(), String> {
    if invalid_exact_name(value) {
        return Err(format!(
            "{label} must be non-blank without control characters"
        ));
    }
    Ok(())
}

fn invalid_exact_name(value: &str) -> bool {
    value.is_empty() || value.trim() != value || value.chars().any(char::is_control)
}

fn normalize_workspace_relative_path(value: &str, label: &str) -> Result<String, String> {
    if value.is_empty()
        || value.trim() != value
        || value.contains('\\')
        || is_platform_qualified_path(value)
    {
        return Err(format!(
            "{label} must be a non-blank workspace-relative forward-slash path"
        ));
    }
    let mut segments = Vec::new();
    for component in std::path::Path::new(value).components() {
        match component {
            std::path::Component::Normal(segment) => segments.push(
                segment
                    .to_str()
                    .ok_or_else(|| format!("{label} must be valid UTF-8"))?,
            ),
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                return Err(format!("{label} cannot escape the workspace with `..`"));
            }
            std::path::Component::RootDir | std::path::Component::Prefix(_) => {
                return Err(format!("{label} must be workspace-relative"));
            }
        }
    }
    if segments.is_empty() {
        return Err(format!("{label} must name a workspace-relative path"));
    }
    Ok(segments.join("/"))
}

fn is_platform_qualified_path(value: &str) -> bool {
    let bytes = value.as_bytes();
    value.starts_with(['/', '\\'])
        || matches!(
            bytes,
            [drive, b':', ..] if drive.is_ascii_alphabetic()
        )
}

fn canonical_kotlin_package_segments(value: &str) -> Result<Vec<String>, String> {
    if value.is_empty() || value.trim() != value {
        return Err("named package selectors require a non-blank package name".to_string());
    }
    let mut segments = Vec::new();
    let mut segment = String::new();
    let mut quoted = false;
    let mut in_backticks = false;
    for character in value.chars() {
        match character {
            '`' if segment.is_empty() && !in_backticks => {
                quoted = true;
                in_backticks = true;
            }
            '`' if in_backticks => in_backticks = false,
            '.' if !in_backticks => {
                segments.push(canonical_kotlin_package_segment(&segment, quoted)?);
                segment.clear();
                quoted = false;
            }
            _ if quoted && !in_backticks => {
                return Err("backticked package segments must end before `.`".to_string());
            }
            _ => segment.push(character),
        }
    }
    if in_backticks {
        return Err("backticked package segments must have a closing backtick".to_string());
    }
    segments.push(canonical_kotlin_package_segment(&segment, quoted)?);
    Ok(segments)
}

fn canonical_kotlin_package_segment(value: &str, quoted: bool) -> Result<String, String> {
    if value.is_empty() {
        return Err("Kotlin package names cannot contain empty segments".to_string());
    }
    if quoted {
        if value.chars().any(|character| {
            character.is_control() || matches!(character, '.' | '/' | '\\' | '[' | ']' | ':')
        }) {
            return Err("backticked package segments contain an invalid character".to_string());
        }
        return Ok(value.to_string());
    }
    if !is_plain_kotlin_identifier(value) {
        return Err(format!(
            "`{value}` is not a canonical Kotlin package segment; use backticks for escaped names"
        ));
    }
    Ok(value.to_string())
}

fn is_plain_kotlin_identifier(value: &str) -> bool {
    plain_kotlin_identifier_validator().is_valid(&serde_json::Value::String(value.to_string()))
        && !is_kotlin_hard_keyword(value)
}

fn plain_kotlin_identifier_validator() -> &'static jsonschema::Validator {
    static VALIDATOR: std::sync::OnceLock<jsonschema::Validator> = std::sync::OnceLock::new();
    VALIDATOR.get_or_init(|| {
        let schema = serde_json::json!({
            "type": "string",
            "pattern": r"^(?:_|\p{L})(?:_|\p{L}|\p{Nd})*$"
        });
        jsonschema::options()
            .with_pattern_options(jsonschema::PatternOptions::regex())
            .build(&schema)
            .expect("the static Kotlin package identifier schema is valid")
    })
}

fn is_kotlin_hard_keyword(value: &str) -> bool {
    matches!(
        value,
        "as" | "break"
            | "class"
            | "continue"
            | "do"
            | "else"
            | "false"
            | "for"
            | "fun"
            | "if"
            | "in"
            | "interface"
            | "is"
            | "null"
            | "object"
            | "package"
            | "return"
            | "super"
            | "this"
            | "throw"
            | "true"
            | "try"
            | "typealias"
            | "typeof"
            | "val"
            | "var"
            | "when"
            | "while"
    )
}

#[derive(Debug, Args, Clone)]
pub struct AgentSymbolArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Symbol query text. Use this for lookup; mutating commands use --symbol <fq-name>.
    #[arg(long)]
    pub query: String,
    /// Exact identity lookup by default; use discovery for fuzzy candidates.
    #[arg(long, value_enum, default_value_t)]
    pub mode: AgentSymbolMode,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long)]
    pub file_hint: Option<String>,
    #[arg(long)]
    pub containing_type: Option<String>,
    /// Maximum discovery candidates.
    #[arg(long, default_value_t = 10)]
    pub limit: u32,
    #[command(flatten)]
    pub view: AgentSymbolViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentExactSymbolSelectorArgs {
    /// Fully-qualified compiler symbol identity.
    #[arg(long)]
    pub symbol: CanonicalSymbolName,
    /// Absolute or workspace-root-relative declaration file returned by exact lookup.
    #[arg(long = "declaration-file")]
    pub declaration_file: WorkspaceDeclarationFile,
    /// Non-negative declaration start offset returned by exact lookup.
    #[arg(long = "declaration-start-offset")]
    pub declaration_start_offset: DeclarationStartOffset,
    /// Optional hard assertion for the declaration kind.
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    /// Optional hard assertion for the containing type.
    #[arg(long = "containing-type")]
    pub containing_type: Option<CanonicalSymbolName>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentReusableSymbolSelectorArgs {
    /// Fully-qualified compiler symbol identity for an explicit selector.
    #[arg(
        long,
        required_unless_present = "selector_handle",
        conflicts_with = "selector_handle"
    )]
    pub symbol: Option<CanonicalSymbolName>,
    /// Declaration file returned by exact lookup for an explicit selector.
    #[arg(
        long = "declaration-file",
        required_unless_present = "selector_handle",
        conflicts_with = "selector_handle"
    )]
    pub declaration_file: Option<WorkspaceDeclarationFile>,
    /// Declaration start offset returned by exact lookup for an explicit selector.
    #[arg(
        long = "declaration-start-offset",
        required_unless_present = "selector_handle",
        conflicts_with = "selector_handle"
    )]
    pub declaration_start_offset: Option<DeclarationStartOffset>,
    /// Optional hard assertion for the declaration kind.
    #[arg(long, value_enum, conflicts_with = "selector_handle")]
    pub kind: Option<AgentSymbolKind>,
    /// Optional hard assertion for the containing type.
    #[arg(long = "containing-type", conflicts_with = "selector_handle")]
    pub containing_type: Option<CanonicalSymbolName>,
    /// Opaque exact selector returned by compiler-backed symbol resolution.
    #[arg(long = "selector-handle")]
    pub selector_handle: Option<AgentSelectorHandle>,
}

#[derive(Debug, Clone)]
pub(crate) enum AgentReusableSymbolSelector {
    Explicit(AgentExactSymbolSelectorArgs),
    Handle(AgentSelectorHandle),
}

impl AgentReusableSymbolSelectorArgs {
    pub(crate) fn into_selector(self) -> Result<AgentReusableSymbolSelector, String> {
        match (
            self.symbol,
            self.declaration_file,
            self.declaration_start_offset,
            self.kind,
            self.containing_type,
            self.selector_handle,
        ) {
            (None, None, None, None, None, Some(handle)) => {
                Ok(AgentReusableSymbolSelector::Handle(handle))
            }
            (
                Some(symbol),
                Some(declaration_file),
                Some(declaration_start_offset),
                kind,
                containing_type,
                None,
            ) => Ok(AgentReusableSymbolSelector::Explicit(
                AgentExactSymbolSelectorArgs {
                    symbol,
                    declaration_file,
                    declaration_start_offset,
                    kind,
                    containing_type,
                },
            )),
            _ => Err(
                "provide either --selector-handle or the complete explicit declaration selector"
                    .to_string(),
            ),
        }
    }
}

#[derive(Debug, Args, Clone)]
pub struct AgentReferencesArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub selector: AgentReusableSymbolSelectorArgs,
    /// Include the selected declaration in reference evidence.
    #[arg(long)]
    pub include_declaration: bool,
    /// Maximum relationship records to return.
    #[arg(long, default_value_t)]
    pub limit: AgentRelationLimit,
    /// Opaque query-bound token from the preceding references page.
    #[arg(long)]
    pub page_token: Option<AgentRelationPageToken>,
    #[command(flatten)]
    pub view: AgentRelationViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentCallsArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub selector: AgentReusableSymbolSelectorArgs,
    /// Maximum call traversal depth.
    #[arg(long, default_value_t)]
    pub depth: AgentRelationDepth,
    /// Maximum relationship records to return.
    #[arg(long, default_value_t)]
    pub limit: AgentRelationLimit,
    /// Opaque query-bound token from the preceding page.
    #[arg(long)]
    pub page_token: Option<AgentRelationPageToken>,
    #[command(flatten)]
    pub view: AgentRelationViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentImplementationsArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub selector: AgentReusableSymbolSelectorArgs,
    /// Maximum relationship records to return.
    #[arg(long, default_value_t)]
    pub limit: AgentRelationLimit,
    /// Opaque query-bound token from the preceding implementations page.
    #[arg(long)]
    pub page_token: Option<AgentRelationPageToken>,
    #[command(flatten)]
    pub view: AgentRelationViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentHierarchyArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub selector: AgentReusableSymbolSelectorArgs,
    /// Type hierarchy direction.
    #[arg(long, value_enum)]
    pub direction: AgentHierarchyDirection,
    /// Maximum type traversal depth.
    #[arg(long, default_value_t)]
    pub depth: AgentRelationDepth,
    /// Maximum relationship records to return.
    #[arg(long, default_value_t)]
    pub limit: AgentRelationLimit,
    /// Opaque query-bound token from the preceding hierarchy page.
    #[arg(long)]
    pub page_token: Option<AgentRelationPageToken>,
    #[command(flatten)]
    pub view: AgentRelationViewArgs,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CanonicalSymbolName(String);

impl CanonicalSymbolName {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for CanonicalSymbolName {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        validate_exact_name(value, "symbol")?;
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceDeclarationFile(String);

impl WorkspaceDeclarationFile {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for WorkspaceDeclarationFile {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        validate_exact_name(value, "declaration file")?;
        let path = std::path::Path::new(value);
        if !matches!(
            path.extension().and_then(|extension| extension.to_str()),
            Some("kt" | "kts")
        ) {
            return Err("declaration file must end in .kt or .kts".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DeclarationStartOffset(u32);

impl DeclarationStartOffset {
    pub(crate) fn get(self) -> u32 {
        self.0
    }
}

impl std::str::FromStr for DeclarationStartOffset {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        value
            .parse::<u32>()
            .map(Self)
            .map_err(|_| "declaration start offset must be a non-negative integer".to_string())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AgentRelationLimit(std::num::NonZeroU8);

impl AgentRelationLimit {
    pub(crate) fn get(self) -> u8 {
        self.0.get()
    }
}

impl Default for AgentRelationLimit {
    fn default() -> Self {
        Self(std::num::NonZeroU8::new(4).expect("relationship default limit is positive"))
    }
}

impl std::fmt::Display for AgentRelationLimit {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(formatter)
    }
}

impl std::str::FromStr for AgentRelationLimit {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let value = value
            .parse::<u16>()
            .map_err(|_| "relationship limit must be an integer from 1 through 200".to_string())?;
        if !(1..=200).contains(&value) {
            return Err("relationship limit must be from 1 through 200".to_string());
        }
        let value = u8::try_from(value)
            .map_err(|_| "relationship limit exceeded its typed range".to_string())?;
        Ok(Self(std::num::NonZeroU8::new(value).ok_or_else(|| {
            "relationship limit must be greater than 0".to_string()
        })?))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AgentRelationDepth(std::num::NonZeroU8);

impl AgentRelationDepth {
    pub(crate) fn get(self) -> u8 {
        self.0.get()
    }
}

impl Default for AgentRelationDepth {
    fn default() -> Self {
        Self(std::num::NonZeroU8::new(1).expect("relationship default depth is positive"))
    }
}

impl std::fmt::Display for AgentRelationDepth {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(formatter)
    }
}

impl std::str::FromStr for AgentRelationDepth {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let value = value
            .parse::<u8>()
            .map_err(|_| "relationship depth must be an integer from 1 through 8".to_string())?;
        if !(1..=8).contains(&value) {
            return Err("relationship depth must be from 1 through 8".to_string());
        }
        Ok(Self(std::num::NonZeroU8::new(value).ok_or_else(|| {
            "relationship depth must be greater than 0".to_string()
        })?))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentRelationPageToken(String);

impl AgentRelationPageToken {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for AgentRelationPageToken {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.len() > 4_096
            || !value.is_ascii()
            || value.chars().any(char::is_control)
            || !value.starts_with("krp1.")
        {
            return Err("relationship page token is malformed".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentSelectorHandle(String);

impl AgentSelectorHandle {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl Serialize for AgentSelectorHandle {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for AgentSelectorHandle {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        let value = String::deserialize(deserializer)?;
        value.parse().map_err(serde::de::Error::custom)
    }
}

impl std::str::FromStr for AgentSelectorHandle {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.len() > 4_096
            || !value.is_ascii()
            || value.chars().any(char::is_control)
            || !value.starts_with("ksh1.")
            || value.len() == "ksh1.".len()
        {
            return Err("selector handle is malformed".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentImpactPageToken(String);

impl AgentImpactPageToken {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for AgentImpactPageToken {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.len() > 256
            || !value.is_ascii()
            || value.chars().any(char::is_control)
            || !value.starts_with("kip1.")
        {
            return Err("impact page token is malformed".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AgentImpactDepth(std::num::NonZeroU8);

impl AgentImpactDepth {
    pub(crate) fn get(self) -> u8 {
        self.0.get()
    }
}

impl Default for AgentImpactDepth {
    fn default() -> Self {
        Self(std::num::NonZeroU8::new(3).expect("impact default depth is positive"))
    }
}

impl std::fmt::Display for AgentImpactDepth {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(formatter)
    }
}

impl std::str::FromStr for AgentImpactDepth {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let value = value
            .parse::<u8>()
            .map_err(|_| "impact depth must be an integer from 1 through 8".to_string())?;
        if !(1..=8).contains(&value) {
            return Err("impact depth must be from 1 through 8".to_string());
        }
        Ok(Self(std::num::NonZeroU8::new(value).ok_or_else(|| {
            "impact depth must be greater than 0".to_string()
        })?))
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AgentHierarchyDirection {
    Supertypes,
    Subtypes,
    Both,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AgentSymbolMode {
    #[default]
    Exact,
    Discovery,
}

#[derive(Debug, Args, Clone)]
pub struct AgentImpactArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub selector: AgentReusableSymbolSelectorArgs,
    /// Maximum source-impact traversal depth.
    #[arg(long, default_value_t)]
    pub depth: AgentImpactDepth,
    /// Maximum source-index impact nodes to return.
    #[arg(long, default_value_t)]
    pub limit: AgentRelationLimit,
    /// Opaque query-bound token from the preceding impact page.
    #[arg(long)]
    pub page_token: Option<AgentImpactPageToken>,
    #[command(flatten)]
    pub view: AgentImpactViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentDiagnosticsArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Absolute or workspace-root-relative Kotlin file to analyze. Repeat for multiple files.
    #[arg(long = "file-path", required = true)]
    pub file_paths: Vec<String>,
    #[arg(long)]
    pub skip_refresh: bool,
    /// Maximum diagnostics for detailed views; compact output is capped at eight records.
    #[arg(long, default_value_t = 500, value_parser = clap::value_parser!(u32).range(1..=500))]
    pub limit: u32,
    /// Opaque continuation token from a preceding diagnostics result.
    #[arg(long)]
    pub page_token: Option<String>,
    #[command(flatten)]
    pub view: AgentDiagnosticsViewArgs,
}

#[derive(Debug, Args, Clone, Default)]
pub struct AgentMutationApplyArgs {
    /// Apply the mutation. Without this flag, Kast only reports the planned request.
    #[arg(long)]
    pub apply: bool,
    /// Stable caller-owned key used to retry and recover this applied mutation.
    #[arg(long)]
    pub idempotency_key: Option<String>,
    #[command(flatten)]
    pub view: AgentMutationViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRenameArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Existing declaration identity to rename.
    #[arg(
        long,
        required_unless_present = "selector_handle",
        conflicts_with = "selector_handle"
    )]
    pub symbol: Option<String>,
    /// Opaque exact selector returned by compiler-backed symbol resolution.
    #[arg(long = "selector-handle", conflicts_with = "symbol")]
    pub selector_handle: Option<AgentSelectorHandle>,
    #[arg(long)]
    pub new_name: String,
    #[arg(long, value_enum, conflicts_with = "selector_handle")]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long, conflicts_with = "selector_handle")]
    pub file_hint: Option<String>,
    #[arg(long, conflicts_with = "selector_handle")]
    pub containing_type: Option<String>,
    #[command(flatten)]
    pub mutation: AgentMutationApplyArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentAddFileArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Absolute or workspace-root-relative path of the Kotlin file to create.
    #[arg(long)]
    pub file_path: String,
    /// File containing the complete content to write.
    #[arg(long)]
    pub content_file: PathBuf,
    #[command(flatten)]
    pub mutation: AgentMutationApplyArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentScopedMutationArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Named declaration scope that receives the content.
    #[arg(long)]
    pub inside_scope: Option<String>,
    /// Absolute or workspace-root-relative file scope that receives the content.
    #[arg(long)]
    pub inside_file: Option<String>,
    /// Placement anchor inside the selected scope.
    #[arg(long)]
    pub at: Option<AgentPlacementAnchor>,
    /// Insert after this named symbol.
    #[arg(long)]
    pub after_symbol: Option<String>,
    /// Insert before this named symbol.
    #[arg(long)]
    pub before_symbol: Option<String>,
    /// File containing the declaration or implementation content.
    #[arg(long)]
    pub content_file: PathBuf,
    #[command(flatten)]
    pub mutation: AgentMutationApplyArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentStatementMutationArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Named function or accessor scope that receives the statement.
    #[arg(long)]
    pub inside_scope: String,
    /// Placement anchor inside the selected executable body.
    #[arg(long)]
    pub at: AgentStatementAnchor,
    /// File containing the statement content.
    #[arg(long)]
    pub content_file: PathBuf,
    #[command(flatten)]
    pub mutation: AgentMutationApplyArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentReplaceDeclarationArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Existing declaration identity to replace.
    #[arg(
        long,
        required_unless_present = "selector_handle",
        conflicts_with = "selector_handle"
    )]
    pub symbol: Option<String>,
    /// Opaque exact selector returned by compiler-backed symbol resolution.
    #[arg(long = "selector-handle", conflicts_with = "symbol")]
    pub selector_handle: Option<AgentSelectorHandle>,
    /// File containing the replacement declaration content.
    #[arg(long)]
    pub content_file: PathBuf,
    #[arg(long, value_enum, conflicts_with = "selector_handle")]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long, conflicts_with = "selector_handle")]
    pub file_hint: Option<String>,
    #[arg(long, conflicts_with = "selector_handle")]
    pub containing_type: Option<String>,
    #[command(flatten)]
    pub mutation: AgentMutationApplyArgs,
}


#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("verify_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentVerifyViewArgs {
    /// Preserve the complete validated command envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed evidence used to explain the result.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected verification fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentVerifyField>,
    /// Return verification counts without capability inventories.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentVerifyField {
    Health,
    Runtime,
    Capabilities,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("workspace_files_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentWorkspaceFilesViewArgs {
    /// Preserve the complete validated workspace-file evidence.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed classification and coverage evidence.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected workspace-file fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentWorkspaceFilesField>,
    /// Return typed cardinalities without file records.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentWorkspaceFilesField {
    Path,
    Module,
    SourceSet,
    Kind,
    Package,
    Index,
    Drift,
    Dirty,
    Evidence,
}

impl AgentWorkspaceFilesField {
    pub(crate) fn canonical(self) -> &'static str {
        match self {
            Self::Path => "path",
            Self::Module => "module",
            Self::SourceSet => "source-set",
            Self::Kind => "kind",
            Self::Package => "package",
            Self::Index => "index",
            Self::Drift => "drift",
            Self::Dirty => "dirty",
            Self::Evidence => "evidence",
        }
    }
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("symbol_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentSymbolViewArgs {
    /// Preserve the complete validated command envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include ranking, member, and next-request evidence.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected symbol result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentSymbolField>,
    /// Return only result, candidate, and relationship counts.
    #[arg(long)]
    pub count: bool,
}

impl AgentSymbolViewArgs {
    pub fn detailed(&self) -> bool {
        self.verbose || self.explain
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentSymbolField {
    Identity,
    SelectorHandle,
    Location,
    Mode,
    Outcome,
    Source,
    Ambiguity,
    Relationships,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("impact_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentImpactViewArgs {
    /// Preserve the complete validated metrics command envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed source-index impact evidence.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected impact result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentImpactField>,
    /// Return impact cardinality without impact nodes.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentImpactField {
    Query,
    Summary,
    Nodes,
    Confidence,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("diagnostics_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentDiagnosticsViewArgs {
    /// Preserve the complete validated command envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed diagnostic step evidence.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected diagnostics result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentDiagnosticsField>,
    /// Return semantic and diagnostic counts without diagnostic records.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentDiagnosticsField {
    Analysis,
    Diagnostics,
    SeverityCounts,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("mutation_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentMutationViewArgs {
    /// Preserve the complete validated command envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed mutation lifecycle evidence.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected mutation result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentMutationField>,
    /// Return mutation state and aggregate counts only.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentMutationField {
    Outcome,
    Deduplicated,
    Edits,
    Files,
    Diagnostics,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("relation_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentRelationViewArgs {
    /// Preserve the complete validated relationship envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed evidence for the bounded relationship page.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected relationship result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentRelationField>,
    /// Return relationship cardinality, coverage, limitations, and page evidence only.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentRelationField {
    Subject,
    Relation,
    Records,
    Page,
    Coverage,
    Limitations,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentSymbolKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
    Parameter,
    Unknown,
}

impl AgentSymbolKind {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Class => "class",
            Self::Interface => "interface",
            Self::Object => "object",
            Self::Function => "function",
            Self::Property => "property",
            Self::Parameter => "parameter",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentPlacementAnchor {
    BodyStart,
    BodyEnd,
    FileTop,
    FileBottom,
    AfterImports,
}

impl AgentPlacementAnchor {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::BodyStart => "body-start",
            Self::BodyEnd => "body-end",
            Self::FileTop => "file-top",
            Self::FileBottom => "file-bottom",
            Self::AfterImports => "after-imports",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentStatementAnchor {
    BodyEnd,
}

impl AgentStatementAnchor {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::BodyEnd => "body-end",
        }
    }
}
