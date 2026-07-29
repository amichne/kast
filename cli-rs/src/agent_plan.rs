use crate::agent_adapter;
use crate::cli::{
    AgentAddFileArgs, AgentCommand, AgentMutationApplyArgs, AgentPlacementAnchor, AgentRenameArgs,
    AgentReplaceDeclarationArgs, AgentScopedMutationArgs, AgentStatementAnchor,
    AgentStatementMutationArgs, KastChangeArgs, KastChangeCommand,
};
use crate::error::{CliError, Result};
use crate::{config, manifest, output};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::fs::{self, File, OpenOptions};
use std::io::{IsTerminal, Read, Write};
use std::path::{Path, PathBuf};
use uuid::{Uuid, Version};

const PLAN_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredPlan {
    schema_version: u32,
    plan_id: Uuid,
    workspace_root: String,
    operation: StoredOperation,
    content_sha256: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(
    tag = "operation",
    rename_all = "kebab-case",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum StoredOperation {
    Rename { symbol: String, new_name: String },
    AddFile { path: PathBuf },
    AddDeclaration { path: PathBuf },
    AddImplementation { scope: String },
    AddStatement { scope: String },
    Replace { symbol: String },
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ChangeResult {
    plan_id: String,
    operation: &'static str,
    plan: Value,
    next: String,
}

pub(crate) fn run_change(args: KastChangeArgs) -> Result<i32> {
    let workspace_root = canonical_workspace_root()?;
    let mut operation = StoredOperation::from(args.command);
    let content = operation.requires_content().then(read_stdin).transpose()?;
    let plan_id = Uuid::new_v4();
    let paths = PlanPaths::new(plan_id);
    ensure_private_directory(&paths.directory)?;

    let preview_content_path = match content.as_deref() {
        Some(content) => {
            write_private_file(&paths.preview_content, content)?;
            Some(paths.preview_content.as_path())
        }
        None => None,
    };
    let preview = match agent_adapter::projected_value(operation.command(
        workspace_root.clone(),
        preview_content_path,
        false,
        None,
    )?) {
        Ok(preview) => preview,
        Err(error) => {
            remove_if_exists(&paths.preview_content);
            return Err(error);
        }
    };
    if preview.get("ok") != Some(&Value::Bool(true)) {
        remove_if_exists(&paths.preview_content);
        return agent_adapter::print_projected_value(preview);
    }
    let preview_result = match projected_result(&preview) {
        Ok(result) => result,
        Err(error) => {
            remove_if_exists(&paths.preview_content);
            return Err(error);
        }
    };
    if let Err(error) = operation.normalize_from_preview(preview_result) {
        remove_if_exists(&paths.preview_content);
        return Err(error);
    }
    let public_plan = public_plan(preview_result);

    if content.is_some()
        && let Err(error) = rename_private_file(&paths.preview_content, &paths.content)
    {
        remove_if_exists(&paths.preview_content);
        return Err(error);
    }
    let stored = StoredPlan {
        schema_version: PLAN_SCHEMA_VERSION,
        plan_id,
        workspace_root: workspace_root.display().to_string(),
        operation,
        content_sha256: content.as_deref().map(manifest::sha256_bytes),
    };
    if let Err(error) = write_plan(&paths.plan, &stored) {
        remove_if_exists(&paths.content);
        return Err(error);
    }

    let result = ChangeResult {
        plan_id: plan_id.hyphenated().to_string(),
        operation: stored.operation.name(),
        plan: public_plan,
        next: format!("kast apply {}", plan_id.hyphenated()),
    };
    output::print_structured(&result, crate::cli::OutputFormat::Toon)?;
    Ok(0)
}

pub(crate) fn run_apply(raw_plan_id: String) -> Result<i32> {
    let plan_id = parse_plan_id(&raw_plan_id)?;
    let paths = PlanPaths::new(plan_id);
    let plan_bytes = read_private_file(&paths.plan, "KAST_PLAN_UNAVAILABLE")?;
    let plan: StoredPlan = serde_json::from_slice(&plan_bytes).map_err(|error| {
        CliError::new(
            "KAST_PLAN_INVALID",
            format!("The stored change plan is malformed: {error}"),
        )
    })?;
    validate_plan(&plan, plan_id)?;

    let workspace_root = canonical_workspace_root()?;
    if plan.workspace_root != workspace_root.display().to_string() {
        return Err(CliError::new(
            "KAST_PLAN_WORKSPACE_MISMATCH",
            format!(
                "Plan {plan_id} belongs to {}, not {}.",
                plan.workspace_root,
                workspace_root.display()
            ),
        ));
    }

    let content_path = if plan.operation.requires_content() {
        let contents = read_private_file(&paths.content, "KAST_PLAN_CONTENT_UNAVAILABLE")?;
        let expected = plan.content_sha256.as_deref().ok_or_else(|| {
            CliError::new(
                "KAST_PLAN_INVALID",
                "The stored content-bearing plan has no content digest.",
            )
        })?;
        if manifest::sha256_bytes(&contents) != expected {
            return Err(CliError::new(
                "KAST_PLAN_CONTENT_CHANGED",
                "The stored change content no longer matches the validated plan.",
            ));
        }
        Some(paths.content.as_path())
    } else {
        if plan.content_sha256.is_some() || paths.content.exists() {
            return Err(CliError::new(
                "KAST_PLAN_INVALID",
                "The stored rename plan unexpectedly contains change content.",
            ));
        }
        None
    };

    let envelope = agent_adapter::projected_value(plan.operation.command(
        workspace_root,
        content_path,
        true,
        Some(plan_id.hyphenated().to_string()),
    )?)?;
    let outcome = envelope
        .get("result")
        .and_then(|result| result.get("execution"))
        .and_then(|execution| execution.get("outcome"))
        .and_then(Value::as_str);
    if outcome == Some("FAILED") {
        let result = envelope.get("result").cloned().ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The failed change returned no typed failure result.",
            )
        })?;
        agent_adapter::print_agent_result(result)?;
        return Ok(1);
    }
    if envelope.get("ok") != Some(&Value::Bool(true)) {
        return agent_adapter::print_projected_value(envelope);
    }
    if outcome != Some("SUCCEEDED") {
        return Err(CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The applied change did not return a successful execution outcome.",
        ));
    }
    agent_adapter::print_projected_value(envelope)?;
    fs::remove_file(&paths.plan)?;
    if plan.operation.requires_content() {
        fs::remove_file(&paths.content)?;
    }
    sync_directory(&paths.directory)?;
    Ok(0)
}

impl From<KastChangeCommand> for StoredOperation {
    fn from(command: KastChangeCommand) -> Self {
        match command {
            KastChangeCommand::Rename { symbol, new_name } => Self::Rename { symbol, new_name },
            KastChangeCommand::AddFile { path } => Self::AddFile { path },
            KastChangeCommand::AddDeclaration { path } => Self::AddDeclaration { path },
            KastChangeCommand::AddImplementation { scope } => Self::AddImplementation { scope },
            KastChangeCommand::AddStatement { scope } => Self::AddStatement { scope },
            KastChangeCommand::Replace { symbol } => Self::Replace { symbol },
        }
    }
}

impl StoredOperation {
    fn name(&self) -> &'static str {
        match self {
            Self::Rename { .. } => "rename",
            Self::AddFile { .. } => "add-file",
            Self::AddDeclaration { .. } => "add-declaration",
            Self::AddImplementation { .. } => "add-implementation",
            Self::AddStatement { .. } => "add-statement",
            Self::Replace { .. } => "replace",
        }
    }

    fn requires_content(&self) -> bool {
        !matches!(self, Self::Rename { .. })
    }

    fn normalize_from_preview(&mut self, preview: &Value) -> Result<()> {
        let normalized = preview
            .get("plan")
            .and_then(|plan| plan.get("filePath"))
            .and_then(Value::as_str);
        match self {
            Self::AddFile { path } | Self::AddDeclaration { path } => {
                *path = PathBuf::from(normalized.ok_or_else(|| {
                    CliError::new(
                        "KAST_INVALID_AGENT_RESULT",
                        "The validated change plan returned no normalized file path.",
                    )
                })?);
            }
            _ => {}
        }
        Ok(())
    }

    fn command(
        &self,
        workspace_root: PathBuf,
        content_file: Option<&Path>,
        apply: bool,
        idempotency_key: Option<String>,
    ) -> Result<AgentCommand> {
        let runtime = agent_adapter::agent_runtime(workspace_root);
        let mutation = AgentMutationApplyArgs {
            apply,
            idempotency_key,
            view: Default::default(),
        };
        let content_file = || {
            content_file.map(Path::to_path_buf).ok_or_else(|| {
                CliError::new(
                    "KAST_PLAN_CONTENT_UNAVAILABLE",
                    "This change operation requires stored Kotlin content.",
                )
            })
        };
        Ok(match self {
            Self::Rename { symbol, new_name } => AgentCommand::Rename(AgentRenameArgs {
                runtime,
                symbol: Some(symbol.clone()),
                selector_handle: None,
                new_name: new_name.clone(),
                kind: None,
                file_hint: None,
                containing_type: None,
                mutation,
            }),
            Self::AddFile { path } => AgentCommand::AddFile(AgentAddFileArgs {
                runtime,
                file_path: path.display().to_string(),
                content_file: content_file()?,
                mutation,
            }),
            Self::AddDeclaration { path } => {
                AgentCommand::AddDeclaration(AgentScopedMutationArgs {
                    runtime,
                    inside_scope: None,
                    inside_file: Some(path.display().to_string()),
                    at: Some(AgentPlacementAnchor::FileBottom),
                    after_symbol: None,
                    before_symbol: None,
                    content_file: content_file()?,
                    mutation,
                })
            }
            Self::AddImplementation { scope } => {
                AgentCommand::AddImplementation(AgentScopedMutationArgs {
                    runtime,
                    inside_scope: Some(scope.clone()),
                    inside_file: None,
                    at: Some(AgentPlacementAnchor::BodyEnd),
                    after_symbol: None,
                    before_symbol: None,
                    content_file: content_file()?,
                    mutation,
                })
            }
            Self::AddStatement { scope } => {
                AgentCommand::AddStatement(AgentStatementMutationArgs {
                    runtime,
                    inside_scope: scope.clone(),
                    at: AgentStatementAnchor::BodyEnd,
                    content_file: content_file()?,
                    mutation,
                })
            }
            Self::Replace { symbol } => {
                AgentCommand::ReplaceDeclaration(AgentReplaceDeclarationArgs {
                    runtime,
                    symbol: Some(symbol.clone()),
                    selector_handle: None,
                    content_file: content_file()?,
                    kind: None,
                    file_hint: None,
                    containing_type: None,
                    mutation,
                })
            }
        })
    }
}

struct PlanPaths {
    directory: PathBuf,
    plan: PathBuf,
    content: PathBuf,
    preview_content: PathBuf,
}

impl PlanPaths {
    fn new(plan_id: Uuid) -> Self {
        let directory = manifest::default_install_root().join("state/agent-plans");
        let id = plan_id.hyphenated();
        Self {
            plan: directory.join(format!("{id}.json")),
            content: directory.join(format!("{id}.content")),
            preview_content: directory.join(format!(".{id}.preview-{}.content", Uuid::new_v4())),
            directory,
        }
    }
}

fn canonical_workspace_root() -> Result<PathBuf> {
    let root = config::resolve_workspace_root(None)?;
    root.canonicalize().map_err(|error| {
        CliError::new(
            "WORKSPACE_ROOT_UNAVAILABLE",
            format!(
                "The workspace root {} could not be canonicalized: {error}",
                root.display()
            ),
        )
    })
}

fn parse_plan_id(raw: &str) -> Result<Uuid> {
    let plan_id = Uuid::parse_str(raw)
        .ok()
        .filter(|id| id.get_version() == Some(Version::Random))
        .filter(|id| id.hyphenated().to_string() == raw)
        .ok_or_else(|| {
            CliError::new(
                "CLI_USAGE",
                "Plan ids must be canonical lowercase version-4 UUIDs returned by `kast change`.",
            )
        })?;
    Ok(plan_id)
}

fn validate_plan(plan: &StoredPlan, expected_id: Uuid) -> Result<()> {
    if plan.schema_version != PLAN_SCHEMA_VERSION {
        return Err(CliError::new(
            "KAST_PLAN_VERSION_UNSUPPORTED",
            format!(
                "Plan {} uses unsupported private schema version {}.",
                plan.plan_id, plan.schema_version
            ),
        ));
    }
    if plan.plan_id != expected_id {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The stored change plan identity does not match its requested id.",
        ));
    }
    if plan.operation.requires_content() != plan.content_sha256.is_some() {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The stored change plan has inconsistent content evidence.",
        ));
    }
    Ok(())
}

fn read_stdin() -> Result<Vec<u8>> {
    let mut stdin = std::io::stdin();
    if stdin.is_terminal() {
        return Err(CliError::new(
            "CLI_USAGE",
            "Pipe the Kotlin content to stdin.",
        ));
    }
    let mut content = Vec::new();
    stdin.read_to_end(&mut content)?;
    if content.is_empty() {
        return Err(CliError::new(
            "CLI_USAGE",
            "Piped Kotlin content must not be empty.",
        ));
    }
    Ok(content)
}

fn ensure_private_directory(path: &Path) -> Result<()> {
    fs::create_dir_all(path)?;
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(CliError::new(
            "KAST_PLAN_STORE_INVALID",
            format!(
                "The private plan store {} is not a directory.",
                path.display()
            ),
        ));
    }
    set_mode(path, 0o700)?;
    Ok(())
}

fn write_plan(path: &Path, plan: &StoredPlan) -> Result<()> {
    let mut encoded = serde_json::to_vec(plan)?;
    encoded.push(b'\n');
    let temporary = path.with_extension(format!("json.tmp-{}", Uuid::new_v4()));
    write_private_file(&temporary, &encoded)?;
    if let Err(error) = rename_private_file(&temporary, path) {
        remove_if_exists(&temporary);
        return Err(error);
    }
    Ok(())
}

fn write_private_file(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let result = (|| {
        let mut file = options.open(path)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        set_mode(path, 0o600)
    })();
    if result.is_err() {
        remove_if_exists(path);
    }
    result
}

fn rename_private_file(from: &Path, to: &Path) -> Result<()> {
    if to.exists() {
        return Err(CliError::new(
            "KAST_PLAN_ALREADY_EXISTS",
            format!(
                "Refusing to overwrite private plan data at {}.",
                to.display()
            ),
        ));
    }
    fs::rename(from, to)?;
    sync_directory(
        to.parent()
            .expect("private plan files always have a parent directory"),
    )
}

fn read_private_file(path: &Path, missing_code: &'static str) -> Result<Vec<u8>> {
    let metadata = fs::symlink_metadata(path).map_err(|error| {
        CliError::new(
            missing_code,
            format!(
                "Private plan data {} is unavailable: {error}",
                path.display()
            ),
        )
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            format!(
                "Private plan data {} is not a regular file.",
                path.display()
            ),
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o077 != 0 {
            return Err(CliError::new(
                "KAST_PLAN_PERMISSIONS_INVALID",
                format!(
                    "Private plan data {} is readable outside its owner.",
                    path.display()
                ),
            ));
        }
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW);
    }
    let mut file = options.open(path)?;
    let opened = file.metadata()?;
    if !opened.is_file() {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            format!(
                "Private plan data {} changed while opening.",
                path.display()
            ),
        ));
    }
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes)?;
    Ok(bytes)
}

fn set_mode(path: &Path, mode: u32) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(mode))?;
    }
    #[cfg(not(unix))]
    let _ = (path, mode);
    Ok(())
}

fn sync_directory(path: &Path) -> Result<()> {
    File::open(path)?.sync_all()?;
    Ok(())
}

fn projected_result(envelope: &Value) -> Result<&Value> {
    envelope.get("result").ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The validated change completed without a result.",
        )
    })
}

fn public_plan(preview: &Value) -> Value {
    let plan = preview.get("plan").cloned().unwrap_or_else(|| json!({}));
    strip_private_fields(plan)
}

fn strip_private_fields(value: Value) -> Value {
    match value {
        Value::Object(fields) => Value::Object(
            fields
                .into_iter()
                .filter_map(|(key, value)| {
                    (!matches!(
                        key.as_str(),
                        "contentFile"
                            | "help"
                            | "method"
                            | "mutates"
                            | "ok"
                            | "schemaVersion"
                            | "applyRequired"
                            | "type"
                    ))
                    .then(|| (key, strip_private_fields(value)))
                })
                .collect(),
        ),
        Value::Array(items) => Value::Array(items.into_iter().map(strip_private_fields).collect()),
        scalar => scalar,
    }
}

fn remove_if_exists(path: &Path) {
    let _ = fs::remove_file(path);
}
