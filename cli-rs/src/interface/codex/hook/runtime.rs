use crate::cli::{CodexHookEvent, KastHarness, RuntimeStartDeadlineUnixEpochMillis};
use crate::config::{CodexHooksConfig, IndexerAutoStartConsent, KastConfig};
use crate::error::{CliError, Result};
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::ffi::OsString;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};

const KOTLIN_SOURCE_SUFFIX: &str = concat!(".", "kt");
const KOTLIN_SCRIPT_SUFFIX: &str = concat!(".", "kts");
const AGENT_PROVIDER_ENV: &str = "KAST_AGENT_PROVIDER";
const AGENT_RESOURCE_ROOT_ENV: &str = "KAST_AGENT_RESOURCE_ROOT";

include!("runtime/activation.rs");
include!("runtime/session_start.rs");

#[derive(Debug, Deserialize)]
struct HookInput {
    #[serde(default)]
    cwd: Option<PathBuf>,
    #[serde(default, alias = "toolName")]
    tool_name: Option<String>,
    #[serde(default, alias = "toolInput")]
    tool_input: Value,
    #[serde(default, alias = "toolResponse")]
    tool_response: Value,
}

pub(crate) fn run(event: CodexHookEvent) -> Result<i32> {
    let activation = match AgentHarnessActivation::from_environment(event) {
        Ok(activation) => activation,
        Err(failure) => {
            print_json(&activation_rejection(
                event,
                failure.harness,
                &failure.error,
            ))?;
            return Ok(0);
        }
    };
    let harness = activation.as_ref().map(|activation| activation.harness);
    let output = activation
        .as_ref()
        .map(AgentHarnessActivation::validate)
        .transpose()
        .and_then(|_| evaluate(event, harness))
        .unwrap_or_else(|error| {
            if error.code == "KAST_AGENT_RESOURCES_INCOMPATIBLE" {
                activation_rejection(event, harness, &error)
            } else {
                agent_context(harness, event, format!("{}: {}", error.code, error.message))
            }
        });
    print_json(&output)?;
    Ok(0)
}

fn evaluate(event: CodexHookEvent, harness: Option<KastHarness>) -> Result<Value> {
    if event == CodexHookEvent::PreToolUse {
        return Ok(json!({}));
    }
    if !hook_enabled(&KastConfig::load_global()?.codex.hooks, event) {
        return Ok(json!({}));
    }
    let input = read_input()?;
    evaluate_with_runner(event, harness, input, run_kast)
}

fn activation_rejection(
    event: CodexHookEvent,
    harness: Option<KastHarness>,
    error: &CliError,
) -> Value {
    let details = error
        .details
        .iter()
        .map(|(key, value)| format!("{key}: {value}"))
        .collect::<Vec<_>>()
        .join("\n");
    let message = if details.is_empty() {
        format!("{}: {}", error.code, error.message)
    } else {
        format!("{}: {}\n{details}", error.code, error.message)
    };
    if event == CodexHookEvent::PreToolUse {
        json!({
            "permissionDecision": "deny",
            "permissionDecisionReason": message,
        })
    } else if harness == Some(KastHarness::Copilot) {
        json!({"additionalContext": message})
    } else {
        json!({
            "continue": false,
            "stopReason": error.message,
            "systemMessage": message,
            "hookSpecificOutput": {
                "hookEventName": event.codex_name(),
                "additionalContext": message
            }
        })
    }
}

fn evaluate_with_runner(
    event: CodexHookEvent,
    harness: Option<KastHarness>,
    input: HookInput,
    runner: impl Fn(&[OsString]) -> Result<String>,
) -> Result<Value> {
    evaluate_with_consent_and_runner(
        event,
        harness,
        input,
        crate::config::exact_worktree_auto_start_consent,
        runner,
    )
}

fn evaluate_with_consent_and_runner(
    event: CodexHookEvent,
    harness: Option<KastHarness>,
    input: HookInput,
    consent: impl FnOnce(&Path) -> Result<IndexerAutoStartConsent>,
    runner: impl Fn(&[OsString]) -> Result<String>,
) -> Result<Value> {
    let cwd = crate::config::normalize(input.cwd.clone().unwrap_or(std::env::current_dir()?));
    let Some(workspace) = crate::config::find_workspace_root_from(&cwd) else {
        return Ok(json!({}));
    };
    let output = match event {
        CodexHookEvent::SessionStart => {
            let harness = harness.ok_or_else(|| {
                CliError::new(
                    "KAST_AGENT_RESOURCES_INCOMPATIBLE",
                    "SessionStart requires a validated agent-harness identity.",
                )
            })?;
            let consent = consent(&workspace)?;
            session_start_with_consent_and_runner(harness, &workspace, consent, runner)
        }
        CodexHookEvent::PreToolUse => json!({}),
        CodexHookEvent::PostToolUse => post_tool_use_with_runner(&input, &workspace, &cwd, runner),
    };
    Ok(output)
}

fn hook_enabled(config: &CodexHooksConfig, event: CodexHookEvent) -> bool {
    config.enabled
        && match event {
            CodexHookEvent::SessionStart => config.session_start,
            CodexHookEvent::PreToolUse => true,
            CodexHookEvent::PostToolUse => config.post_tool_use,
        }
}

fn read_input() -> Result<HookInput> {
    let mut bytes = Vec::new();
    io::stdin().read_to_end(&mut bytes)?;
    serde_json::from_slice(&bytes).map_err(|error| {
        CliError::new(
            "CODEX_HOOK_INPUT_INVALID",
            format!("Codex hook input must be one JSON object: {error}"),
        )
    })
}

fn post_tool_use_with_runner(
    input: &HookInput,
    workspace: &Path,
    cwd: &Path,
    runner: impl Fn(&[OsString]) -> Result<String>,
) -> Value {
    let paths = qualifying_kotlin_paths(input, workspace, cwd);
    if paths.is_empty() {
        return json!({});
    }
    let status_args = [
        OsString::from("--output"),
        OsString::from("json"),
        OsString::from("status"),
        OsString::from("--workspace-root"),
        workspace.as_os_str().to_os_string(),
    ];
    if !matches!(runner(&status_args), Ok(status) if status_is_healthy(&status, workspace)) {
        return json!({});
    }
    let diagnostics = paths
        .iter()
        .map(|path| {
            advisory_result(
                "Kast diagnostics",
                runner(&diagnostics_args(workspace, path)),
            )
        })
        .collect::<Vec<_>>()
        .join("\n");
    additional_context(CodexHookEvent::PostToolUse, diagnostics)
}

fn diagnostics_args(workspace: &Path, path: &str) -> [OsString; 8] {
    [
        OsString::from("--output"),
        OsString::from("json"),
        OsString::from("agent"),
        OsString::from("diagnostics"),
        OsString::from("--workspace-root"),
        workspace.as_os_str().to_os_string(),
        OsString::from("--file-path"),
        OsString::from(path),
    ]
}

fn advisory_result(label: &str, result: Result<String>) -> String {
    match result {
        Ok(output) if output.is_empty() => format!("{label}: completed"),
        Ok(output) => format!("{label}: completed\n{output}"),
        Err(error) => format!(
            "{label}: advisory failure\n{}: {}",
            error.code, error.message
        ),
    }
}

fn status_is_healthy(status: &str, workspace: &Path) -> bool {
    let Ok(status) = serde_json::from_str::<Value>(status) else {
        return false;
    };
    let root = status.get("workspaceRoot").and_then(Value::as_str);
    let selected = status.get("selected");
    root == Some(workspace.to_string_lossy().as_ref())
        && selected
            .and_then(|value| value.get("ready"))
            .and_then(Value::as_bool)
            == Some(true)
        && selected
            .and_then(|value| value.pointer("/runtimeStatus/healthy"))
            .and_then(Value::as_bool)
            == Some(true)
        && selected
            .and_then(|value| value.pointer("/descriptor/workspaceRoot"))
            .and_then(Value::as_str)
            == root
}

fn qualifying_kotlin_paths(input: &HookInput, workspace: &Path, cwd: &Path) -> BTreeSet<String> {
    let Some(tool_name) = input.tool_name.as_deref() else {
        return BTreeSet::new();
    };
    if !matches!(
        tool_name.to_ascii_lowercase().as_str(),
        "apply_patch" | "applypatch" | "edit" | "write"
    ) || response_is_failure(&input.tool_response)
    {
        return BTreeSet::new();
    }
    kotlin_paths(&input.tool_input.to_string(), workspace, cwd)
}

fn response_is_failure(value: &Value) -> bool {
    find_field(value, &["ok", "success"]).and_then(Value::as_bool) == Some(false)
        || find_field(value, &["exit_code", "exitCode"])
            .and_then(Value::as_i64)
            .is_some_and(|code| code != 0)
        || find_field(value, &["isError", "is_error"]).and_then(Value::as_bool) == Some(true)
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

fn kotlin_paths(value: &str, workspace: &Path, cwd: &Path) -> BTreeSet<String> {
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
            let absolute = if path.is_absolute() {
                path.to_path_buf()
            } else {
                cwd.join(path)
            };
            let relative = absolute.strip_prefix(workspace).ok()?.to_path_buf();
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

fn additional_context(event: CodexHookEvent, context: String) -> Value {
    json!({
        "hookSpecificOutput": {
            "hookEventName": event.codex_name(),
            "additionalContext": context
        }
    })
}

fn agent_context(harness: Option<KastHarness>, event: CodexHookEvent, context: String) -> Value {
    if harness == Some(KastHarness::Copilot) {
        json!({"additionalContext": context})
    } else {
        additional_context(event, context)
    }
}

fn print_json(value: &Value) -> Result<()> {
    let stdout = io::stdout();
    let mut lock = stdout.lock();
    serde_json::to_writer(&mut lock, value)?;
    lock.write_all(b"\n")?;
    Ok(())
}
