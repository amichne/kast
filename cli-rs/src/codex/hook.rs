use crate::agent::{
    AgentTaskHookOperation, AgentTaskHookResult, AgentTaskState, run_agent_task_hook,
};
use crate::cli::{AgentCommand, Cli, CodexHookEvent, Command as CliCommand};
use crate::error::{CliError, Result};
use clap::Parser;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::fs::{self, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

const STATE_SCHEMA_VERSION: u32 = 3;
const KOTLIN_SOURCE_SUFFIX: &str = concat!(".", "kt");
const KOTLIN_SCRIPT_SUFFIX: &str = concat!(".", "kts");

#[derive(Debug, Deserialize)]
struct HookInput {
    #[serde(alias = "sessionId")]
    session_id: String,
    #[serde(default)]
    cwd: Option<PathBuf>,
    #[serde(default, alias = "toolName")]
    tool_name: Option<String>,
    #[serde(default, alias = "toolInput")]
    tool_input: Value,
}

#[derive(Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct SessionState {
    schema_version: u32,
    session_id: String,
    workspace_root: String,
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
    crate::machine::mark_codex_hook_trusted()?;
    let workspace = resolve_workspace(input.cwd.as_deref())?;
    let state_path = state_path(&input.session_id)?;
    match event {
        CodexHookEvent::SessionStart => session_start(&input, &workspace, &state_path),
        CodexHookEvent::PreToolUse => pre_tool_use(&input, &workspace),
        CodexHookEvent::PostToolUse => post_tool_use(&input, &workspace),
        CodexHookEvent::Stop => stop(&input, &workspace),
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
    crate::agent::resolve_agent_task_start_path(&path)
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
    let mut state = read_state_for_workspace(state_path, workspace)?.unwrap_or(SessionState {
        schema_version: STATE_SCHEMA_VERSION,
        session_id: input.session_id.clone(),
        workspace_root: workspace.display().to_string(),
    });
    state.session_id.clone_from(&input.session_id);
    state.workspace_root = workspace.display().to_string();
    let task = run_agent_task_hook(
        AgentTaskHookOperation::Begin,
        workspace,
        "codex",
        &input.session_id,
    )?;
    write_state(state_path, &state)?;
    Ok(additional_context(
        CodexHookEvent::SessionStart,
        agent_task_context("begin", &task)?,
    ))
}

fn pre_tool_use(input: &HookInput, workspace: &Path) -> Result<Value> {
    let Some(tool_name) = input.tool_name.as_deref() else {
        return Ok(json!({}));
    };
    let serialized = input.tool_input.to_string();
    let paths = kotlin_paths(&serialized, workspace);
    if paths.is_empty() || !is_generic_mutation(tool_name, &serialized) {
        return Ok(json!({}));
    }
    match run_agent_task_hook(
        AgentTaskHookOperation::Status,
        workspace,
        "codex",
        &input.session_id,
    ) {
        Ok(task)
            if matches!(
                task.state(),
                AgentTaskState::Active | AgentTaskState::Blocked
            ) => {}
        Ok(task) => {
            return Ok(pre_tool_denial(format!(
                "AGENT_TASK_NOT_OPEN: task {} is {:?}; begin or repair the exact-workspace task before a generic Kotlin write.",
                task.task_id(),
                task.state(),
            )));
        }
        Err(error) => {
            return Ok(pre_tool_denial(format!(
                "{}: {}",
                error.code, error.message
            )));
        }
    }
    Ok(pre_tool_denial(format!(
        "KAST_TYPED_ROUTE_REQUIRED: try the corresponding plan-first Kast mutation for {} and preserve its typed outcome before a generic edit.",
        paths.into_iter().collect::<Vec<_>>().join(", ")
    )))
}

fn post_tool_use(input: &HookInput, workspace: &Path) -> Result<Value> {
    let context = match run_agent_task_hook(
        AgentTaskHookOperation::Status,
        workspace,
        "codex",
        &input.session_id,
    ) {
        Ok(task) => agent_task_context("status", &task)?,
        Err(error) => format!("{}: {}", error.code, error.message),
    };
    Ok(additional_context(CodexHookEvent::PostToolUse, context))
}

fn stop(input: &HookInput, workspace: &Path) -> Result<Value> {
    match run_agent_task_hook(
        AgentTaskHookOperation::Status,
        workspace,
        "codex",
        &input.session_id,
    ) {
        Ok(task) if task.state() == AgentTaskState::Complete => Ok(json!({})),
        Ok(task) => Ok(json!({
            "decision": "block",
            "reason": format!(
                "AGENT_TASK_EXPLICIT_FINISH_REQUIRED: task {} is {:?}; run kast-agent-task finish before stopping.",
                task.task_id(),
                task.state(),
            ),
        })),
        Err(error) => Ok(json!({
            "decision": "block",
            "reason": format!("{}: {}", error.code, error.message),
        })),
    }
}

fn agent_task_context(operation: &str, task: &AgentTaskHookResult) -> Result<String> {
    Ok(format!("operation: {operation}\n{}", task.to_toon()?))
}

fn pre_tool_denial(reason: String) -> Value {
    json!({
        "hookSpecificOutput": {
            "hookEventName": CodexHookEvent::PreToolUse.codex_name(),
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    })
}

fn additional_context(event: CodexHookEvent, context: String) -> Value {
    json!({
        "hookSpecificOutput": {
            "hookEventName": event.codex_name(),
            "additionalContext": context
        }
    })
}

fn read_state(path: &Path) -> Result<Option<SessionState>> {
    match fs::read(path) {
        Ok(bytes) => {
            let state: SessionState = serde_json::from_slice(&bytes).map_err(|error| {
                CliError::new(
                    "CODEX_HOOK_STATE_INVALID",
                    format!("Persisted Codex hook state is invalid: {error}"),
                )
            })?;
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
    let result = (|| -> Result<()> {
        serde_json::to_writer_pretty(&mut file, state)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}

fn parsed_agent_command(command: &str) -> Option<AgentCommand> {
    let arguments = shlex::split(command)?;
    let executable = arguments.first()?;
    if Path::new(executable).file_name()?.to_str()? != "kast" {
        return None;
    }
    let cli = Cli::try_parse_from(arguments).ok()?;
    match cli.command? {
        CliCommand::Agent(args) => args.command,
        CliCommand::Help { .. }
        | CliCommand::Version
        | CliCommand::Context(_)
        | CliCommand::Setup(_)
        | CliCommand::Ready(_)
        | CliCommand::Repair(_)
        | CliCommand::Status(_)
        | CliCommand::Machine(_)
        | CliCommand::Demo(_)
        | CliCommand::Developer(_)
        | CliCommand::Doctor(_) => None,
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
            let (index, extension_length) = token
                .find(KOTLIN_SCRIPT_SUFFIX)
                .map(|index| (index, KOTLIN_SCRIPT_SUFFIX.len()))
                .or_else(|| {
                    token
                        .find(KOTLIN_SOURCE_SUFFIX)
                        .map(|index| (index, KOTLIN_SOURCE_SUFFIX.len()))
                })?;
            if index == 0 {
                return None;
            }
            let path = Path::new(&token[..index + extension_length]);
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
    fn machine_entrypoint_is_recognized_as_a_typed_agent_command() {
        assert!(matches!(
            parsed_agent_command("/tmp/machine/bin/kast agent verify"),
            Some(AgentCommand::Verify(_)),
        ));
    }

    #[test]
    fn kotlin_path_parser_ignores_bare_extensions_and_globs() {
        let workspace = Path::new("/workspace");
        let source_glob = format!("*.{}", "kt");
        let script_glob = format!("*.{}", "kts");
        assert!(kotlin_paths(&format!("{source_glob} {script_glob}"), workspace).is_empty());
        let source = format!("src/Sample.{}", "kt");
        assert_eq!(kotlin_paths(&source, workspace), BTreeSet::from([source]));
    }
}
