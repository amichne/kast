use crate::cli::{CodexHookEvent, KastHarness};
use crate::config::{CodexHooksConfig, KastConfig};
use crate::error::{CliError, Result};
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::ffi::OsString;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;

const KOTLIN_SOURCE_SUFFIX: &str = concat!(".", "kt");
const KOTLIN_SCRIPT_SUFFIX: &str = concat!(".", "kts");
const AGENT_PROVIDER_ENV: &str = "KAST_AGENT_PROVIDER";
const AGENT_RESOURCE_ROOT_ENV: &str = "KAST_AGENT_RESOURCE_ROOT";

#[derive(Debug)]
struct AgentHarnessActivation {
    harness: KastHarness,
    plugin_root: PathBuf,
}

#[derive(Debug)]
struct AgentHarnessActivationFailure {
    harness: Option<KastHarness>,
    error: CliError,
}

impl AgentHarnessActivation {
    fn from_environment(
        event: CodexHookEvent,
    ) -> std::result::Result<Option<Self>, AgentHarnessActivationFailure> {
        if !matches!(
            event,
            CodexHookEvent::SessionStart | CodexHookEvent::PreToolUse
        ) {
            return Ok(None);
        }
        let provider = std::env::var_os(AGENT_PROVIDER_ENV).ok_or_else(|| {
            activation_identity_failure(
                None,
                "Kast agent-harness provider identity is required for activation.",
            )
        })?;
        let harness = match provider.to_str() {
            Some("codex") => KastHarness::Codex,
            Some("claude") => KastHarness::Claude,
            Some("copilot") => KastHarness::Copilot,
            Some(provider) => {
                return Err(activation_identity_failure(
                    None,
                    format!("Unknown agent harness `{provider}`."),
                ));
            }
            None => {
                return Err(activation_identity_failure(
                    None,
                    "Kast agent-harness provider identity must be UTF-8.",
                ));
            }
        };
        let plugin_root = std::env::var_os(AGENT_RESOURCE_ROOT_ENV).ok_or_else(|| {
            activation_identity_failure(
                Some(harness),
                "Kast agent-harness resource-root identity is required for activation.",
            )
        })?;
        let plugin_root = PathBuf::from(plugin_root);
        if !plugin_root.is_absolute() {
            return Err(activation_identity_failure(
                Some(harness),
                "Kast agent-harness resource-root identity must be absolute.",
            ));
        }
        Ok(Some(Self {
            harness,
            plugin_root,
        }))
    }

    fn validate(&self) -> Result<()> {
        crate::install::validate_agent_harness_activation(self.harness, &self.plugin_root)
    }
}

fn activation_identity_failure(
    harness: Option<KastHarness>,
    message: impl Into<String>,
) -> AgentHarnessActivationFailure {
    AgentHarnessActivationFailure {
        harness,
        error: CliError::new("KAST_AGENT_RESOURCES_INCOMPATIBLE", message),
    }
}

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
        .and_then(|_| evaluate(event))
        .unwrap_or_else(|error| {
            if error.code == "KAST_AGENT_RESOURCES_INCOMPATIBLE" {
                activation_rejection(event, harness, &error)
            } else {
                additional_context(event, format!("{}: {}", error.code, error.message))
            }
        });
    print_json(&output)?;
    Ok(0)
}

fn evaluate(event: CodexHookEvent) -> Result<Value> {
    if event == CodexHookEvent::PreToolUse {
        return Ok(json!({}));
    }
    if !hook_enabled(&KastConfig::load_global()?.codex.hooks, event) {
        return Ok(json!({}));
    }
    let input = read_input()?;
    evaluate_with_runner(event, input, run_kast)
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
    input: HookInput,
    runner: impl Fn(&[OsString]) -> Result<String>,
) -> Result<Value> {
    let cwd = crate::config::normalize(input.cwd.clone().unwrap_or(std::env::current_dir()?));
    let Some(workspace) = crate::config::find_workspace_root_from(&cwd) else {
        return Ok(json!({}));
    };
    Ok(match event {
        CodexHookEvent::SessionStart => session_start_with_runner(&workspace, runner),
        CodexHookEvent::PreToolUse => json!({}),
        CodexHookEvent::PostToolUse => post_tool_use_with_runner(&input, &workspace, &cwd, runner),
    })
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

fn session_start_with_runner(
    workspace: &Path,
    runner: impl FnOnce(&[OsString]) -> Result<String>,
) -> Value {
    let args = [
        OsString::from("--output"),
        OsString::from("json"),
        OsString::from("developer"),
        OsString::from("runtime"),
        OsString::from("up"),
        OsString::from("--workspace-root"),
        workspace.as_os_str().to_os_string(),
        OsString::from("--accept-indexing"),
    ];
    match runner(&args) {
        Ok(_) => json!({}),
        Err(error) => additional_context(
            CodexHookEvent::SessionStart,
            advisory_result("Kast session launch", Err(error)),
        ),
    }
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

fn run_kast(args: &[OsString]) -> Result<String> {
    let binary = std::env::current_exe()?;
    let output = Command::new(&binary).args(args).output()?;
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if output.status.success() {
        return Ok(stdout);
    }
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    let message = if stderr.is_empty() { stdout } else { stderr };
    let mut error = CliError::new(
        "CODEX_HOOK_COMMAND_FAILED",
        format!(
            "{} exited with {}: {message}",
            binary.display(),
            output.status
        ),
    );
    error.details.insert(
        "command".to_string(),
        args.iter()
            .map(|argument| argument.to_string_lossy())
            .collect::<Vec<_>>()
            .join(" "),
    );
    Err(error)
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

fn print_json(value: &Value) -> Result<()> {
    let stdout = io::stdout();
    let mut lock = stdout.lock();
    serde_json::to_writer(&mut lock, value)?;
    lock.write_all(b"\n")?;
    Ok(())
}
