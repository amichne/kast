use crate::cli::CodexHookEvent;
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
    let output = evaluate(event).unwrap_or_else(|error| {
        additional_context(event, format!("{}: {}", error.code, error.message))
    });
    print_json(&output)?;
    Ok(0)
}

fn evaluate(event: CodexHookEvent) -> Result<Value> {
    if !hook_enabled(&KastConfig::load_global()?.codex.hooks, event) {
        return Ok(json!({}));
    }
    let input = read_input()?;
    let cwd = input.cwd.clone().unwrap_or(std::env::current_dir()?);
    let workspace = crate::config::resolve_workspace_root_from(&cwd);
    Ok(match event {
        CodexHookEvent::SessionStart => session_start(&workspace),
        CodexHookEvent::PostToolUse => post_tool_use(&input, &workspace, &cwd),
    })
}

fn hook_enabled(config: &CodexHooksConfig, event: CodexHookEvent) -> bool {
    config.enabled
        && match event {
            CodexHookEvent::SessionStart => config.session_start,
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

fn session_start(workspace: &Path) -> Value {
    let args = [
        OsString::from("--output"),
        OsString::from("json"),
        OsString::from("developer"),
        OsString::from("runtime"),
        OsString::from("up"),
        OsString::from("--workspace-root"),
        workspace.as_os_str().to_os_string(),
        OsString::from("--backend"),
        OsString::from("idea"),
    ];
    additional_context(
        CodexHookEvent::SessionStart,
        advisory_result("Kast session launch", run_kast(&args)),
    )
}

fn post_tool_use(input: &HookInput, workspace: &Path, cwd: &Path) -> Value {
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
        OsString::from("--backend"),
        OsString::from("idea"),
    ];
    let status = match run_kast(&status_args) {
        Ok(status) if status_is_healthy(&status, workspace) => status,
        Ok(status) => {
            return additional_context(
                CodexHookEvent::PostToolUse,
                format!("Kast status is unhealthy; diagnostics skipped.\n{status}"),
            );
        }
        Err(error) => {
            return additional_context(
                CodexHookEvent::PostToolUse,
                format!(
                    "Kast status is unhealthy; diagnostics skipped.\n{}: {}",
                    error.code, error.message
                ),
            );
        }
    };
    let diagnostics = paths
        .iter()
        .map(|path| {
            advisory_result(
                "Kast diagnostics",
                run_kast(&diagnostics_args(workspace, path)),
            )
        })
        .collect::<Vec<_>>()
        .join("\n");
    additional_context(
        CodexHookEvent::PostToolUse,
        format!("Kast status is healthy.\n{status}\n{diagnostics}"),
    )
}

fn diagnostics_args(workspace: &Path, path: &str) -> [OsString; 10] {
    [
        OsString::from("--output"),
        OsString::from("json"),
        OsString::from("agent"),
        OsString::from("diagnostics"),
        OsString::from("--workspace-root"),
        workspace.as_os_str().to_os_string(),
        OsString::from("--backend"),
        OsString::from("idea"),
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

#[cfg(test)]
mod tests {
    use super::*;

    fn input(tool_name: &str, tool_input: Value, tool_response: Value) -> HookInput {
        HookInput {
            cwd: None,
            tool_name: Some(tool_name.to_string()),
            tool_input,
            tool_response,
        }
    }

    #[test]
    fn successful_write_collects_every_kotlin_path() {
        let input = input(
            "apply_patch",
            json!({"patch": "*** Update File: src/A.kt\n*** Add File: build.gradle.kts"}),
            json!({"success": true}),
        );

        assert_eq!(
            qualifying_kotlin_paths(&input, Path::new("/workspace"), Path::new("/workspace")),
            BTreeSet::from(["build.gradle.kts".to_string(), "src/A.kt".to_string()])
        );
    }

    #[test]
    fn diagnostics_command_targets_one_file() {
        assert_eq!(
            diagnostics_args(Path::new("/workspace"), "src/A.kt"),
            [
                "--output",
                "json",
                "agent",
                "diagnostics",
                "--workspace-root",
                "/workspace",
                "--backend",
                "idea",
                "--file-path",
                "src/A.kt",
            ]
            .map(OsString::from)
        );
    }

    #[test]
    fn failed_or_non_kotlin_edits_are_ignored() {
        let failed = input(
            "Edit",
            json!({"file_path": "src/A.kt"}),
            json!({"success": false}),
        );
        let non_kotlin = input(
            "Write",
            json!({"file_path": "README.md"}),
            json!({"success": true}),
        );

        assert!(
            qualifying_kotlin_paths(&failed, Path::new("/workspace"), Path::new("/workspace"))
                .is_empty()
        );
        assert!(
            qualifying_kotlin_paths(
                &non_kotlin,
                Path::new("/workspace"),
                Path::new("/workspace")
            )
            .is_empty()
        );
    }

    #[test]
    fn nested_session_resolves_root_relative_kotlin_paths() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace = temp.path().join("workspace");
        let nested = workspace.join("module");
        std::fs::create_dir_all(&nested).expect("nested workspace");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("workspace marker");
        let input = input(
            "Write",
            json!({"file_path": "src/A.kt"}),
            json!({"success": true}),
        );

        let resolved = crate::config::resolve_workspace_root_from(&nested);

        assert_eq!(resolved, workspace);
        assert_eq!(
            qualifying_kotlin_paths(&input, &resolved, &nested),
            BTreeSet::from(["module/src/A.kt".to_string()])
        );
    }

    #[test]
    fn status_requires_a_healthy_exact_root() {
        let healthy = json!({
            "workspaceRoot": "/workspace",
            "selected": {
                "ready": true,
                "descriptor": {"workspaceRoot": "/workspace"},
                "runtimeStatus": {"healthy": true}
            }
        });

        assert!(status_is_healthy(
            &healthy.to_string(),
            Path::new("/workspace")
        ));
        assert!(!status_is_healthy(
            &healthy.to_string(),
            Path::new("/other")
        ));
    }

    #[test]
    fn failures_render_as_advisory_context() {
        let context = advisory_result(
            "Kast diagnostics",
            Err(CliError::new(
                "DIAGNOSTICS_FAILED",
                "diagnostics unavailable",
            )),
        );

        assert!(context.contains("advisory failure"));
    }

    #[test]
    fn global_and_per_hook_switches_gate_events() {
        let disabled = CodexHooksConfig {
            enabled: false,
            session_start: true,
            post_tool_use: true,
        };
        let session_only = CodexHooksConfig {
            enabled: true,
            session_start: true,
            post_tool_use: false,
        };

        assert!(!hook_enabled(&disabled, CodexHookEvent::SessionStart));
        assert!(hook_enabled(&session_only, CodexHookEvent::SessionStart));
        assert!(!hook_enabled(&session_only, CodexHookEvent::PostToolUse));
    }
}
