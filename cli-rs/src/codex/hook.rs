use crate::cli::{AgentCommand, Cli, CodexHookEvent, Command as CliCommand};
use crate::error::{CliError, Result};
use clap::Parser;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

const STATE_SCHEMA_VERSION: u32 = 2;
const READINESS_TIMEOUT: Duration = Duration::from_secs(5);
const AUTHORITY_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CodexAuthorityManifest {
    schema_version: u32,
    authority: CodexDeclaredAuthority,
}

#[derive(Debug, Deserialize)]
#[serde(
    tag = "kind",
    rename_all = "kebab-case",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum CodexDeclaredAuthority {
    SourceTemplate {
        command: PathBuf,
        plugin_version: String,
        cli_version: String,
    },
    Release {
        command: PathBuf,
        plugin_version: String,
        cli_version: String,
        release_revision: String,
    },
    LocalDevelopment {
        command: PathBuf,
        plugin_version: String,
        cli_version: String,
        generation_id: String,
    },
}

#[derive(Debug, Deserialize)]
struct HookInput {
    #[serde(alias = "sessionId")]
    session_id: String,
    #[serde(default)]
    cwd: Option<PathBuf>,
    #[serde(default)]
    source: Option<String>,
    #[serde(default, alias = "toolName")]
    tool_name: Option<String>,
    #[serde(default, alias = "toolInput")]
    tool_input: Value,
    #[serde(default, alias = "toolResponse")]
    tool_response: Value,
    #[serde(default, alias = "lastAssistantMessage")]
    last_assistant_message: Option<String>,
}

#[derive(Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct SessionState {
    schema_version: u32,
    session_id: String,
    workspace_root: String,
    git_common_directory: Option<String>,
    git_directory: Option<String>,
    linked_worktree: Option<String>,
    baseline_head: Option<String>,
    head: Option<String>,
    plugin_version: String,
    kast_version: String,
    binary_path: String,
    readiness: ReadinessEvidence,
    baseline_kotlin: BTreeMap<String, String>,
    typed_attempts: Vec<TypedAttempt>,
    affected_files: BTreeSet<String>,
    operation_ids: BTreeSet<String>,
    diagnostics: BTreeMap<String, DiagnosticsEvidence>,
    reported_blockers: Vec<ReportedBlocker>,
}

#[derive(Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ReadinessEvidence {
    ready: bool,
    repair_plan_available: bool,
    ready_outcome: HookCommandOutcome,
    repair_plan_outcome: Option<HookCommandOutcome>,
}

#[derive(Debug, Default, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum HookCommandOutcome {
    Succeeded,
    Failed,
    TimedOut,
    #[default]
    Unavailable,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct TypedAttempt {
    command: String,
    paths: BTreeSet<String>,
    outcome: TypedAttemptOutcome,
    code: Option<String>,
    fallback_eligible: bool,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum TypedAttemptOutcome {
    Succeeded,
    Failed,
    Unrecognized,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DiagnosticsEvidence {
    content_sha256: String,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ReportedBlocker {
    paths: BTreeSet<String>,
    code: String,
    message: String,
}

pub(crate) fn run(event: CodexHookEvent) -> Result<i32> {
    match evaluate(event) {
        Ok(output) => {
            print_json(&output)?;
            Ok(0)
        }
        Err(error) => {
            print_json(&json!({
                "continue": false,
                "stopReason": format!("{}: {}", error.code, error.message),
                "systemMessage": error.to_response()
            }))?;
            Ok(1)
        }
    }
}

fn evaluate(event: CodexHookEvent) -> Result<Value> {
    let input = read_input()?;
    validate_declared_authority()?;
    let workspace = resolve_workspace(input.cwd.as_deref())?;
    let state_path = state_path(&input.session_id)?;
    match event {
        CodexHookEvent::SessionStart => session_start(&input, &workspace, &state_path),
        CodexHookEvent::SubagentStart => subagent_start(&input, &workspace, &state_path),
        CodexHookEvent::PreToolUse => pre_tool_use(&input, &workspace, &state_path),
        CodexHookEvent::PostToolUse => post_tool_use(&input, &workspace, &state_path),
        CodexHookEvent::Stop => stop(&input, &workspace, &state_path),
    }
}

fn read_input() -> Result<HookInput> {
    let mut bytes = Vec::new();
    io::stdin().read_to_end(&mut bytes)?;
    serde_json::from_slice(&bytes).map_err(|error| {
        let mut cli_error = CliError::new(
            "CODEX_HOOK_INPUT_INVALID",
            "Codex hook input must be one JSON object.",
        );
        cli_error
            .details
            .insert("cause".to_string(), error.to_string());
        cli_error
    })
}

fn resolve_workspace(input: Option<&Path>) -> Result<PathBuf> {
    let path = match input {
        Some(path) => path.to_path_buf(),
        None => std::env::current_dir()?,
    };
    let canonical = path.canonicalize()?;
    let Some(root) = git_value(&canonical, &["rev-parse", "--show-toplevel"]) else {
        return Ok(canonical);
    };
    PathBuf::from(root).canonicalize().map_err(Into::into)
}

fn state_path(session_id: &str) -> Result<PathBuf> {
    if session_id.is_empty()
        || !session_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
    {
        return Err(CliError::new(
            "CODEX_HOOK_SESSION_INVALID",
            "Hook session IDs may contain only letters, digits, dash, underscore, and dot.",
        ));
    }
    let data_root = std::env::var_os("PLUGIN_DATA").ok_or_else(|| {
        CliError::new(
            "CODEX_HOOK_DATA_UNAVAILABLE",
            "PLUGIN_DATA is required for Codex hook state.",
        )
    })?;
    Ok(PathBuf::from(data_root)
        .join("sessions")
        .join(format!("{session_id}.json")))
}

fn session_start(input: &HookInput, workspace: &Path, state_path: &Path) -> Result<Value> {
    let compact = input.source.as_deref() == Some("compact");
    let existing = if compact {
        read_state_for_workspace(state_path, workspace)?
    } else {
        None
    };
    let plugin_version = plugin_version();
    let kast_version = crate::cli::version().to_string();
    let readiness = readiness(workspace);
    let current_head = git_value(workspace, &["rev-parse", "HEAD"]);
    let mut state = existing.unwrap_or(SessionState {
        schema_version: STATE_SCHEMA_VERSION,
        session_id: input.session_id.clone(),
        workspace_root: workspace.display().to_string(),
        git_common_directory: git_path(workspace, "--git-common-dir"),
        git_directory: git_path(workspace, "--git-dir"),
        linked_worktree: linked_worktree(workspace),
        baseline_head: current_head.clone(),
        head: current_head.clone(),
        plugin_version: plugin_version.clone(),
        kast_version: kast_version.clone(),
        binary_path: current_binary(),
        readiness: ReadinessEvidence::default(),
        baseline_kotlin: dirty_kotlin(workspace)?,
        typed_attempts: Vec::new(),
        affected_files: BTreeSet::new(),
        operation_ids: BTreeSet::new(),
        diagnostics: BTreeMap::new(),
        reported_blockers: Vec::new(),
    });
    state.workspace_root = workspace.display().to_string();
    state.git_common_directory = git_path(workspace, "--git-common-dir");
    state.git_directory = git_path(workspace, "--git-dir");
    state.linked_worktree = linked_worktree(workspace);
    state.head = current_head;
    state.plugin_version = plugin_version.clone();
    state.kast_version = kast_version.clone();
    state.binary_path = current_binary();
    state.readiness = readiness;
    write_state(state_path, &state)?;

    let coherence = if versions_coherent(&plugin_version, &kast_version) {
        "coherent"
    } else {
        "mismatch: update Kast and reinstall kast@kast from the same release"
    };
    let preparation = if state.readiness.ready {
        "ready"
    } else if state.readiness.repair_plan_available {
        "not ready; a read-only repair plan is available"
    } else {
        "not ready"
    };
    let source = if compact { "compact recovery" } else { "start" };
    Ok(additional_context(
        CodexHookEvent::SessionStart,
        format!(
            "Kast Codex {source}: workspace={}, binary={}, version={}, plugin={}, coherence={coherence}, readiness={preparation}, baselineKotlin={}",
            workspace.display(),
            state.binary_path,
            kast_version,
            plugin_version,
            state.baseline_kotlin.len()
        ),
    ))
}

fn subagent_start(input: &HookInput, workspace: &Path, state_path: &Path) -> Result<Value> {
    let current_head = git_value(workspace, &["rev-parse", "HEAD"]);
    let mut state = read_state_for_workspace(state_path, workspace)?.unwrap_or(SessionState {
        schema_version: STATE_SCHEMA_VERSION,
        session_id: input.session_id.clone(),
        workspace_root: workspace.display().to_string(),
        git_common_directory: git_path(workspace, "--git-common-dir"),
        git_directory: git_path(workspace, "--git-dir"),
        linked_worktree: linked_worktree(workspace),
        baseline_head: current_head.clone(),
        head: current_head.clone(),
        plugin_version: plugin_version(),
        kast_version: crate::cli::version().to_string(),
        binary_path: current_binary(),
        readiness: readiness(workspace),
        baseline_kotlin: dirty_kotlin(workspace)?,
        typed_attempts: Vec::new(),
        affected_files: BTreeSet::new(),
        operation_ids: BTreeSet::new(),
        diagnostics: BTreeMap::new(),
        reported_blockers: Vec::new(),
    });
    state.workspace_root = workspace.display().to_string();
    state.git_common_directory = git_path(workspace, "--git-common-dir");
    state.git_directory = git_path(workspace, "--git-dir");
    state.linked_worktree = linked_worktree(workspace);
    state.head = current_head;
    write_state(state_path, &state)?;
    Ok(additional_context(
        CodexHookEvent::SubagentStart,
        format!(
            "Kast subagent workspace={} gitCommonDirectory={} gitDirectory={} linkedWorktree={} head={}",
            state.workspace_root,
            state
                .git_common_directory
                .as_deref()
                .unwrap_or("unavailable"),
            state.git_directory.as_deref().unwrap_or("unavailable"),
            state.linked_worktree.as_deref().unwrap_or("primary"),
            state.head.as_deref().unwrap_or("unavailable")
        ),
    ))
}

fn pre_tool_use(input: &HookInput, workspace: &Path, state_path: &Path) -> Result<Value> {
    let Some(tool_name) = input.tool_name.as_deref() else {
        return Ok(json!({}));
    };
    let serialized = input.tool_input.to_string();
    let paths = kotlin_paths(&serialized, workspace);
    if paths.is_empty() || !is_generic_mutation(tool_name, &serialized) {
        return Ok(json!({}));
    }
    let state = read_state_for_workspace(state_path, workspace)?.unwrap_or_default();
    let allowed: BTreeSet<&str> = state
        .typed_attempts
        .iter()
        .filter(|attempt| attempt.fallback_eligible)
        .flat_map(|attempt| attempt.paths.iter().map(String::as_str))
        .collect();
    let denied: Vec<_> = paths
        .iter()
        .filter(|path| !allowed.contains(path.as_str()))
        .cloned()
        .collect();
    if denied.is_empty() {
        return Ok(json!({}));
    }
    Ok(json!({
        "hookSpecificOutput": {
            "hookEventName": CodexHookEvent::PreToolUse.codex_name(),
            "permissionDecision": "deny",
            "permissionDecisionReason": format!(
                "KAST_TYPED_ROUTE_REQUIRED: try the corresponding plan-first Kast mutation for {} and preserve its typed outcome before a generic edit.",
                denied.join(", ")
            )
        }
    }))
}

fn post_tool_use(input: &HookInput, workspace: &Path, state_path: &Path) -> Result<Value> {
    let Some(command) = tool_command(&input.tool_input) else {
        return Ok(json!({}));
    };
    let Some(agent_command) = parsed_agent_command(command) else {
        return Ok(json!({}));
    };
    let mut state = required_state(state_path, workspace)?;
    let response = response_text(&input.tool_response);
    let failed = response_is_failure(&input.tool_response, &response);
    let structured = structured_response(&response);
    let mut paths = kotlin_paths(command, workspace);
    paths.extend(kotlin_paths(&response, workspace));
    state.affected_files.extend(paths.iter().cloned());
    state.operation_ids.extend(operation_ids(&response));

    if let Some(command_name) = semantic_mutation_name(&agent_command) {
        let code = structured.as_ref().and_then(typed_failure_code);
        let outcome = match structured
            .as_ref()
            .and_then(|value| value.get("ok"))
            .and_then(Value::as_bool)
        {
            Some(false) if code.is_some() => TypedAttemptOutcome::Failed,
            Some(true) if !failed => TypedAttemptOutcome::Succeeded,
            Some(false) | Some(true) | None => TypedAttemptOutcome::Unrecognized,
        };
        state.typed_attempts.push(TypedAttempt {
            command: command_name.to_string(),
            paths: paths.clone(),
            outcome,
            fallback_eligible: outcome == TypedAttemptOutcome::Failed && !paths.is_empty(),
            code,
        });
    }
    if !failed
        && let Some(diagnostic_paths) =
            validated_diagnostics_paths(&agent_command, structured.as_ref(), workspace)
    {
        for path in diagnostic_paths {
            let absolute = workspace.join(&path);
            state.diagnostics.insert(
                path,
                DiagnosticsEvidence {
                    content_sha256: content_hash(&absolute)?,
                },
            );
        }
    }
    write_state(state_path, &state)?;
    Ok(json!({}))
}

fn stop(input: &HookInput, workspace: &Path, state_path: &Path) -> Result<Value> {
    let mut state = required_state(state_path, workspace)?;
    let current = kotlin_since_baseline(workspace, &state)?;
    let changed: BTreeMap<_, _> = current
        .into_iter()
        .filter(|(path, hash)| state.baseline_kotlin.get(path) != Some(hash))
        .collect();
    let missing: Vec<_> = changed
        .iter()
        .filter(|(path, hash)| {
            state
                .diagnostics
                .get(path.as_str())
                .is_none_or(|evidence| &evidence.content_sha256 != *hash)
        })
        .map(|(path, _)| path.clone())
        .collect();
    if missing.is_empty() {
        return Ok(json!({}));
    }
    if let Some(blocker) = explicitly_reported_blocker(input, &state, &missing) {
        state.reported_blockers.push(blocker);
        write_state(state_path, &state)?;
        return Ok(json!({}));
    }
    Ok(json!({
        "decision": "block",
        "reason": format!(
            "KAST_DIAGNOSTICS_REQUIRED: run current diagnostics for {} or explicitly report the recorded typed blocker.",
            missing.join(", ")
        )
    }))
}

fn additional_context(event: CodexHookEvent, context: String) -> Value {
    json!({
        "hookSpecificOutput": {
            "hookEventName": event.codex_name(),
            "additionalContext": context
        }
    })
}

fn readiness(workspace: &Path) -> ReadinessEvidence {
    let Ok(binary) = std::env::current_exe() else {
        return ReadinessEvidence::default();
    };
    let workspace_arg = workspace.as_os_str();
    let mut ready_command = Command::new(&binary);
    ready_command
        .args([
            "--output",
            "json",
            "ready",
            "--for",
            "agent",
            "--workspace-root",
        ])
        .arg(workspace_arg);
    let ready_outcome = command_outcome(ready_command, READINESS_TIMEOUT)
        .unwrap_or(HookCommandOutcome::Unavailable);
    let ready = ready_outcome == HookCommandOutcome::Succeeded;
    let repair_plan_outcome = if ready {
        None
    } else {
        let mut repair_command = Command::new(binary);
        repair_command
            .args([
                "--output",
                "json",
                "repair",
                "--for",
                "agent",
                "--workspace-root",
            ])
            .arg(workspace_arg);
        Some(
            command_outcome(repair_command, READINESS_TIMEOUT)
                .unwrap_or(HookCommandOutcome::Unavailable),
        )
    };
    ReadinessEvidence {
        ready,
        repair_plan_available: repair_plan_outcome == Some(HookCommandOutcome::Succeeded),
        ready_outcome,
        repair_plan_outcome,
    }
}

fn command_outcome(mut command: Command, timeout: Duration) -> io::Result<HookCommandOutcome> {
    command.stdout(Stdio::null()).stderr(Stdio::null());
    let mut child = command.spawn()?;
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(status) = child.try_wait()? {
            return Ok(if status.success() {
                HookCommandOutcome::Succeeded
            } else {
                HookCommandOutcome::Failed
            });
        }
        if Instant::now() >= deadline {
            child.kill()?;
            child.wait()?;
            return Ok(HookCommandOutcome::TimedOut);
        }
        thread::sleep(Duration::from_millis(10));
    }
}

fn plugin_version() -> String {
    let Some(root) = std::env::var_os("PLUGIN_ROOT") else {
        return "unknown".to_string();
    };
    let manifest = PathBuf::from(root).join(".codex-plugin/plugin.json");
    fs::read(&manifest)
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok())
        .and_then(|value| value.get("version")?.as_str().map(str::to_string))
        .unwrap_or_else(|| "unknown".to_string())
}

fn authority_manifest() -> Result<CodexAuthorityManifest> {
    let root = std::env::var_os("PLUGIN_ROOT").ok_or_else(|| {
        CliError::new(
            "CODEX_AUTHORITY_UNAVAILABLE",
            "PLUGIN_ROOT is required to resolve the generated Kast authority manifest.",
        )
    })?;
    let path = PathBuf::from(root).join("assets/kast-authority.json");
    let manifest: CodexAuthorityManifest =
        serde_json::from_slice(&fs::read(&path)?).map_err(|error| {
            CliError::new(
                "CODEX_AUTHORITY_INVALID",
                format!(
                    "Invalid generated Kast authority manifest {}: {error}.",
                    path.display()
                ),
            )
        })?;
    if manifest.schema_version != AUTHORITY_SCHEMA_VERSION {
        return Err(CliError::new(
            "CODEX_AUTHORITY_UNSUPPORTED",
            format!(
                "Kast authority schema {} is unsupported; expected {}.",
                manifest.schema_version, AUTHORITY_SCHEMA_VERSION,
            ),
        ));
    }
    Ok(manifest)
}

fn validate_declared_authority() -> Result<()> {
    let manifest = authority_manifest()?;
    let installed_plugin_version = plugin_version();
    match manifest.authority {
        CodexDeclaredAuthority::SourceTemplate {
            command,
            plugin_version,
            cli_version,
        } => {
            if command != Path::new("kast")
                || plugin_version != installed_plugin_version
                || cli_version != crate::cli::version()
                || crate::local_development::active_local_development_receipt()?.is_some()
            {
                return Err(CliError::new(
                    "CODEX_SOURCE_AUTHORITY_MISMATCH",
                    "The repository Codex plugin template does not match the active Kast CLI.",
                ));
            }
        }
        CodexDeclaredAuthority::Release {
            command,
            plugin_version,
            cli_version,
            release_revision,
        } => {
            if command != Path::new("kast")
                || plugin_version != installed_plugin_version
                || cli_version != crate::cli::version()
                || release_revision != crate::cli::release_revision()
                || crate::local_development::active_local_development_receipt()?.is_some()
            {
                return Err(CliError::new(
                    "CODEX_RELEASE_AUTHORITY_MISMATCH",
                    "The installed Codex plugin does not match the active released Kast generation.",
                ));
            }
        }
        CodexDeclaredAuthority::LocalDevelopment {
            command,
            plugin_version,
            cli_version,
            generation_id,
        } => {
            let configured_binary = std::env::var_os("KAST_CODEX_BINARY").map(PathBuf::from);
            let configured_generation = std::env::var("KAST_CODEX_GENERATION").ok();
            let receipt = crate::local_development::verified_active_local_development_receipt()?
                .ok_or_else(|| {
                    CliError::new(
                        "CODEX_LOCAL_AUTHORITY_MISSING",
                        "The generated local Codex plugin no longer has an active local Kast authority.",
                    )
                })?;
            if !command.is_absolute()
                || configured_binary.as_ref() != Some(&command)
                || configured_generation.as_deref() != Some(generation_id.as_str())
                || receipt.entrypoint.effective_target != command
                || receipt.generation_id.as_str() != generation_id
                || plugin_version != installed_plugin_version
                || cli_version != crate::cli::version()
            {
                return Err(CliError::new(
                    "CODEX_LOCAL_GENERATION_MISMATCH",
                    "The generated local Codex plugin does not match the active worktree generation.",
                ));
            }
        }
    }
    Ok(())
}

fn versions_coherent(plugin: &str, kast: &str) -> bool {
    match (base_codex_version(plugin), base_codex_version(kast)) {
        (Some(plugin), Some(kast)) => plugin == kast,
        (None, _) | (_, None) => false,
    }
}

fn base_codex_version(version: &str) -> Option<&str> {
    match version.split_once("+codex.") {
        None if !version.is_empty() => Some(version),
        Some((base, token))
            if !base.is_empty()
                && !token.is_empty()
                && !token.contains('+')
                && token
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_')) =>
        {
            Some(base)
        }
        None | Some(_) => None,
    }
}

fn current_binary() -> String {
    std::env::current_exe()
        .map(|path| path.display().to_string())
        .unwrap_or_else(|_| "unavailable".to_string())
}

fn read_state(path: &Path) -> Result<Option<SessionState>> {
    match fs::read(path) {
        Ok(bytes) => {
            let state: SessionState = serde_json::from_slice(&bytes)?;
            if state.schema_version != STATE_SCHEMA_VERSION {
                let mut error = CliError::new(
                    "CODEX_HOOK_STATE_INCOMPATIBLE",
                    "Persisted Codex hook state has an incompatible schema.",
                );
                error.details.insert(
                    "expectedSchemaVersion".to_string(),
                    STATE_SCHEMA_VERSION.to_string(),
                );
                error.details.insert(
                    "actualSchemaVersion".to_string(),
                    state.schema_version.to_string(),
                );
                return Err(error);
            }
            Ok(Some(state))
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

fn read_state_for_workspace(path: &Path, workspace: &Path) -> Result<Option<SessionState>> {
    let state = read_state(path)?;
    if let Some(state) = &state
        && state.workspace_root != workspace.display().to_string()
    {
        let mut error = CliError::new(
            "CODEX_HOOK_WORKSPACE_MISMATCH",
            "Persisted Codex hook evidence belongs to another workspace or linked worktree.",
        );
        error
            .details
            .insert("stateWorkspace".to_string(), state.workspace_root.clone());
        error.details.insert(
            "eventWorkspace".to_string(),
            workspace.display().to_string(),
        );
        return Err(error);
    }
    Ok(state)
}

fn required_state(path: &Path, workspace: &Path) -> Result<SessionState> {
    read_state_for_workspace(path, workspace)?.ok_or_else(|| {
        CliError::new(
            "CODEX_HOOK_STATE_MISSING",
            "SessionStart must establish hook state before recording or checking Kotlin evidence.",
        )
    })
}

fn write_state(path: &Path, state: &SessionState) -> Result<()> {
    let parent = path.parent().ok_or_else(|| {
        CliError::new("CODEX_HOOK_STATE_INVALID", "Hook state path has no parent.")
    })?;
    fs::create_dir_all(parent)?;
    fs::set_permissions(parent, fs::Permissions::from_mode(0o700))?;
    let temporary = parent.join(format!(
        ".{}.{}.tmp",
        state.session_id,
        uuid::Uuid::new_v4()
    ));
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .mode(0o600)
        .open(&temporary)?;
    serde_json::to_writer_pretty(&mut file, state)?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    fs::rename(&temporary, path)?;
    Ok(())
}

fn dirty_kotlin(workspace: &Path) -> Result<BTreeMap<String, String>> {
    let mut paths = BTreeSet::new();
    for args in [
        vec!["diff", "--name-only", "-z", "HEAD", "--", "*.kt", "*.kts"],
        vec![
            "diff",
            "--name-only",
            "-z",
            "--cached",
            "--",
            "*.kt",
            "*.kts",
        ],
        vec![
            "ls-files",
            "--others",
            "--exclude-standard",
            "-z",
            "--",
            "*.kt",
            "*.kts",
        ],
    ] {
        let output = Command::new("git")
            .args(args)
            .current_dir(workspace)
            .output();
        let Ok(output) = output else {
            continue;
        };
        if !output.status.success() {
            continue;
        }
        for raw in output.stdout.split(|byte| *byte == 0) {
            if raw.is_empty() {
                continue;
            }
            if let Ok(path) = std::str::from_utf8(raw) {
                paths.insert(path.to_string());
            }
        }
    }
    paths
        .into_iter()
        .map(|path| {
            let hash = content_hash(&workspace.join(&path))?;
            Ok((path, hash))
        })
        .collect()
}

fn kotlin_since_baseline(
    workspace: &Path,
    state: &SessionState,
) -> Result<BTreeMap<String, String>> {
    let mut current = dirty_kotlin(workspace)?;
    let Some(baseline_head) = state.baseline_head.as_deref() else {
        return Ok(current);
    };
    let Some(head) = git_value(workspace, &["rev-parse", "HEAD"]) else {
        return Ok(current);
    };
    if baseline_head == head {
        return Ok(current);
    }
    let output = Command::new("git")
        .args([
            "diff",
            "--name-only",
            "-z",
            baseline_head,
            &head,
            "--",
            "*.kt",
            "*.kts",
        ])
        .current_dir(workspace)
        .output()?;
    if !output.status.success() {
        let mut error = CliError::new(
            "CODEX_HOOK_BASELINE_UNAVAILABLE",
            "Kotlin changes cannot be compared with the session's original Git HEAD.",
        );
        error
            .details
            .insert("baselineHead".to_string(), baseline_head.to_string());
        error.details.insert("currentHead".to_string(), head);
        return Err(error);
    }
    for raw in output.stdout.split(|byte| *byte == 0) {
        if raw.is_empty() {
            continue;
        }
        let path = std::str::from_utf8(raw).map_err(|error| {
            CliError::new(
                "CODEX_HOOK_GIT_PATH_INVALID",
                format!("Git returned a non-UTF-8 Kotlin path: {error}"),
            )
        })?;
        current.insert(path.to_string(), content_hash(&workspace.join(path))?);
    }
    Ok(current)
}

fn content_hash(path: &Path) -> Result<String> {
    match fs::read(path) {
        Ok(bytes) => Ok(hex::encode(Sha256::digest(bytes))),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok("deleted".to_string()),
        Err(error) => Err(error.into()),
    }
}

fn git_value(workspace: &Path, args: &[&str]) -> Option<String> {
    let output = Command::new("git")
        .args(args)
        .current_dir(workspace)
        .output()
        .ok()?;
    output
        .status
        .success()
        .then(|| String::from_utf8_lossy(&output.stdout).trim().to_string())
}

fn git_path(workspace: &Path, flag: &str) -> Option<String> {
    git_value(workspace, &["rev-parse", "--path-format=absolute", flag]).map(|value| {
        let path = PathBuf::from(value);
        path.canonicalize().unwrap_or(path).display().to_string()
    })
}

fn linked_worktree(workspace: &Path) -> Option<String> {
    let common = git_path(workspace, "--git-common-dir")?;
    let directory = git_path(workspace, "--git-dir")?;
    (common != directory).then(|| workspace.display().to_string())
}

fn tool_command(input: &Value) -> Option<&str> {
    input
        .get("command")
        .or_else(|| input.get("cmd"))
        .or_else(|| input.pointer("/args/command"))
        .or_else(|| input.pointer("/args/cmd"))?
        .as_str()
}

fn parsed_agent_command(command: &str) -> Option<AgentCommand> {
    let declared = authority_manifest().ok()?.authority;
    let declared_command = match declared {
        CodexDeclaredAuthority::SourceTemplate { command, .. }
        | CodexDeclaredAuthority::Release { command, .. }
        | CodexDeclaredAuthority::LocalDevelopment { command, .. } => command,
    };
    parsed_agent_command_for(command, &declared_command)
}

fn parsed_agent_command_for(command: &str, declared_command: &Path) -> Option<AgentCommand> {
    let arguments = shlex::split(command)?;
    let executable = arguments.first()?;
    if Path::new(executable) != declared_command {
        return None;
    }
    let cli = Cli::try_parse_from(arguments).ok()?;
    match cli.command? {
        CliCommand::Agent(args) => Some(args.command),
        CliCommand::Help { .. }
        | CliCommand::Version
        | CliCommand::Context(_)
        | CliCommand::Setup(_)
        | CliCommand::Ready(_)
        | CliCommand::Repair(_)
        | CliCommand::Status(_)
        | CliCommand::Demo(_)
        | CliCommand::Developer(_)
        | CliCommand::Doctor(_) => None,
    }
}

fn response_text(response: &Value) -> String {
    find_field(response, &["output", "stdout"])
        .and_then(Value::as_str)
        .or_else(|| response.as_str())
        .map_or_else(|| response.to_string(), str::to_string)
}

fn response_is_failure(value: &Value, response: &str) -> bool {
    if find_field(value, &["ok"]).and_then(Value::as_bool) == Some(false) {
        return true;
    }
    if find_field(value, &["exit_code", "exitCode"])
        .and_then(Value::as_i64)
        .is_some_and(|code| code != 0)
    {
        return true;
    }
    let compact = response
        .replace([' ', '\n', '\r', '\t'], "")
        .to_ascii_lowercase();
    compact.contains("ok:false")
        || compact.contains("\"ok\":false")
        || response.lines().any(|line| {
            line.split_once(':').is_some_and(|(key, value)| {
                matches!(key.trim_matches([' ', '"']), "exitCode" | "exit_code")
                    && value
                        .trim_matches([' ', '"', ','])
                        .parse::<i64>()
                        .is_ok_and(|code| code != 0)
            })
        })
}

fn structured_response(response: &str) -> Option<Value> {
    serde_json::from_str(response)
        .ok()
        .or_else(|| toon_format::decode_default(response.trim()).ok())
}

fn typed_failure_code(value: &Value) -> Option<String> {
    value
        .get("code")
        .or_else(|| value.pointer("/error/code"))
        .and_then(Value::as_str)
        .filter(|code| !code.is_empty())
        .map(str::to_string)
}

fn validated_diagnostics_paths(
    command: &AgentCommand,
    response: Option<&Value>,
    workspace: &Path,
) -> Option<BTreeSet<String>> {
    let AgentCommand::Diagnostics(args) = command else {
        return None;
    };
    let response = response?;
    if response.get("ok").and_then(Value::as_bool) != Some(true)
        || response.get("method").and_then(Value::as_str) != Some("agent/diagnostics")
        || response.pointer("/result/ok").and_then(Value::as_bool) != Some(true)
        || response.pointer("/result/type").and_then(Value::as_str)
            != Some("KAST_AGENT_DIAGNOSTICS_RESULT")
    {
        return None;
    }
    let requested = args
        .file_paths
        .iter()
        .flat_map(|path| kotlin_paths(path, workspace))
        .collect::<BTreeSet<_>>();
    let returned = response
        .pointer("/result/filePaths")
        .and_then(Value::as_array)?
        .iter()
        .filter_map(Value::as_str)
        .flat_map(|path| kotlin_paths(path, workspace))
        .collect::<BTreeSet<_>>();
    (!requested.is_empty() && returned == requested).then_some(returned)
}

fn find_field<'a>(value: &'a Value, keys: &[&str]) -> Option<&'a Value> {
    match value {
        Value::Object(object) => keys
            .iter()
            .find_map(|key| object.get(*key))
            .or_else(|| object.values().find_map(|value| find_field(value, keys))),
        Value::Array(values) => values.iter().find_map(|value| find_field(value, keys)),
        Value::Null | Value::Bool(_) | Value::Number(_) | Value::String(_) => None,
    }
}

fn operation_ids(response: &str) -> BTreeSet<String> {
    response
        .lines()
        .filter_map(|line| {
            let (key, value) = line.split_once(':')?;
            matches!(key.trim_matches([' ', '"']), "operationId" | "operation_id")
                .then(|| value.trim_matches([' ', '"', ',']).to_string())
        })
        .filter(|value| !value.is_empty())
        .collect()
}

fn semantic_mutation_name(command: &AgentCommand) -> Option<&'static str> {
    match command {
        AgentCommand::Rename(_) => Some("rename"),
        AgentCommand::AddFile(_) => Some("add-file"),
        AgentCommand::AddDeclaration(_) => Some("add-declaration"),
        AgentCommand::AddImplementation(_) => Some("add-implementation"),
        AgentCommand::AddStatement(_) => Some("add-statement"),
        AgentCommand::ReplaceDeclaration(_) => Some("replace-declaration"),
        AgentCommand::Lsp(_)
        | AgentCommand::Lease(_)
        | AgentCommand::Verify(_)
        | AgentCommand::WorkspaceFiles(_)
        | AgentCommand::Symbol(_)
        | AgentCommand::References(_)
        | AgentCommand::Callers(_)
        | AgentCommand::Callees(_)
        | AgentCommand::Implementations(_)
        | AgentCommand::Hierarchy(_)
        | AgentCommand::Impact(_)
        | AgentCommand::Diagnostics(_)
        | AgentCommand::Operation(_)
        | AgentCommand::Tools(_)
        | AgentCommand::Call(_)
        | AgentCommand::Workflow(_) => None,
    }
}

fn is_generic_mutation(tool_name: &str, serialized_input: &str) -> bool {
    let normalized = tool_name.to_ascii_lowercase();
    if matches!(
        normalized.as_str(),
        "apply_patch" | "applypatch" | "edit" | "multiedit" | "write"
    ) {
        return true;
    }
    matches!(
        normalized.as_str(),
        "bash" | "exec_command" | "shell_command" | "terminal"
    ) && ["sed -i", "perl -pi", "tee ", "rm ", "mv ", "cp ", ">"]
        .into_iter()
        .any(|token| serialized_input.contains(token))
}

fn kotlin_paths(value: &str, workspace: &Path) -> BTreeSet<String> {
    value
        .split(|character: char| {
            character.is_whitespace()
                || matches!(
                    character,
                    '"' | '\'' | ',' | '[' | ']' | '(' | ')' | '{' | '}'
                )
        })
        .filter_map(|token| {
            let token = token
                .trim_matches(|character: char| matches!(character, '*' | ':' | ';' | '`' | '\\'));
            let end = token
                .find(".kts")
                .map(|index| index + 4)
                .or_else(|| token.find(".kt").map(|index| index + 3))?;
            let path = Path::new(&token[..end]);
            let relative = if path.is_absolute() {
                path.strip_prefix(workspace).ok()?.to_path_buf()
            } else {
                path.components().collect()
            };
            Some(
                relative
                    .to_string_lossy()
                    .trim_start_matches("./")
                    .to_string(),
            )
        })
        .filter(|path| !path.is_empty())
        .collect()
}

fn explicitly_reported_blocker(
    input: &HookInput,
    state: &SessionState,
    missing: &[String],
) -> Option<ReportedBlocker> {
    let message = input.last_assistant_message.as_deref()?;
    let normalized = message.to_ascii_lowercase();
    if !normalized.contains("blocker") && !normalized.contains("blocked") {
        return None;
    }
    state
        .typed_attempts
        .iter()
        .filter(|attempt| attempt.fallback_eligible)
        .find_map(|attempt| {
            let code = attempt.code.as_ref()?;
            (attempt.paths.iter().any(|path| missing.contains(path)) && message.contains(code))
                .then(|| ReportedBlocker {
                    paths: attempt.paths.clone(),
                    code: code.clone(),
                    message: message.to_string(),
                })
        })
}

fn print_json(value: &Value) -> Result<()> {
    let stdout = io::stdout();
    let mut lock = stdout.lock();
    serde_json::to_writer(&mut lock, value)?;
    lock.write_all(b"\n")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn internal_hook_commands_are_killed_at_the_typed_timeout_boundary() {
        let mut command = Command::new("sh");
        command.args(["-c", "sleep 1"]);
        assert_eq!(
            command_outcome(command, Duration::from_millis(10)).expect("timeout outcome"),
            HookCommandOutcome::TimedOut
        );
    }

    #[test]
    fn local_cachebusters_preserve_base_version_coherence() {
        assert!(versions_coherent("1.2.3", "1.2.3"));
        assert!(versions_coherent("1.2.3+codex.local", "1.2.3"));
        assert!(versions_coherent("1.2.3+codex.local", "1.2.3+codex.local"));
        assert!(!versions_coherent("1.2.3+codex.local", "1.2.4"));
        assert!(!versions_coherent("1.2.3+codex.", "1.2.3"));
        assert!(!versions_coherent("1.2.3+other.local", "1.2.3"));
    }

    #[test]
    fn local_stable_entrypoint_is_recognized_as_a_typed_agent_command() {
        assert!(matches!(
            parsed_agent_command_for(
                "/tmp/worktree/.kast/local-development/bin/kast agent verify",
                Path::new("/tmp/worktree/.kast/local-development/bin/kast"),
            ),
            Some(AgentCommand::Verify(_)),
        ));
    }

    #[test]
    fn another_kast_basename_is_not_the_declared_agent_command() {
        assert!(
            parsed_agent_command_for(
                "/opt/homebrew/bin/kast agent verify",
                Path::new("/tmp/worktree/.kast/local-development/bin/kast"),
            )
            .is_none()
        );
    }
}
