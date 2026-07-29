mod agent;
#[path = "configuration/bundle.rs"]
mod bundle;
#[path = "configuration/catalog_schema.rs"]
mod catalog_schema;
#[path = "interface/cli.rs"]
mod cli;
#[path = "interface/codex.rs"]
mod codex;
#[path = "configuration/config.rs"]
mod config;
#[path = "operations/contract_gen.rs"]
mod contract_gen;
#[path = "operations/daemon.rs"]
mod daemon;
#[path = "interface/demo.rs"]
mod demo;
#[path = "configuration/error.rs"]
mod error;
#[path = "operations/install.rs"]
mod install;
#[path = "configuration/manifest.rs"]
mod manifest;
#[path = "operations/metrics.rs"]
mod metrics;
#[path = "storage/metrics_database.rs"]
mod metrics_database;
#[path = "interface/output.rs"]
mod output;
#[path = "operations/package.rs"]
mod package;
#[path = "configuration/protocol_schema_versions.rs"]
mod protocol_schema_versions;
#[path = "semantics/repository_intelligence.rs"]
mod repository_intelligence;
#[path = "semantics/rpc.rs"]
mod rpc;
#[path = "execution/runtime.rs"]
mod runtime;
#[path = "operations/self_mgmt.rs"]
mod self_mgmt;
#[path = "storage/source_index_db.rs"]
mod source_index_db;
#[path = "storage/source_index_schema.rs"]
mod source_index_schema;
#[path = "semantics/symbol_query.rs"]
mod symbol_query;
#[path = "semantics/symbol_query_filters.rs"]
mod symbol_query_filters;
#[path = "configuration/validate.rs"]
mod validate;
#[path = "semantics/workspace_inventory.rs"]
mod workspace_inventory;

use clap::{CommandFactory, Parser};
use cli::{Cli, Command, GenerateCommand, KAgentCli, KAgentCommand, OutputFormat};
use error::{CliError, Result};
use serde::Serialize;
use std::env;
use std::io::{self, IsTerminal};
use std::path::{Path, PathBuf};

const SCHEMA_VERSION: u32 = protocol_schema_versions::API_SCHEMA_VERSION;
const AGENT_JSON_DEPRECATION_WARNING: &str =
    "warning: JSON output for `kast agent` is deprecated; omit `--output json` to use TOON.";

fn main() {
    let exit_code = if invoked_as_kagent() {
        kagent_main()
    } else {
        kast_main()
    };
    std::process::exit(exit_code);
}

fn kast_main() -> i32 {
    let exit_code = match parse_cli() {
        Ok(Some(cli)) => {
            warn_for_deprecated_agent_json(&cli);
            let output_format = effective_output_format(cli.output, cli.command.as_ref());
            match run(cli, output_format) {
                Ok(code) => code,
                Err(error) => {
                    let _ = output::print_error(&error, output_format);
                    error_exit_code(&error)
                }
            }
        }
        Ok(None) => 0,
        Err(error) => {
            let _ = output::print_error(&error, requested_output_format());
            error_exit_code(&error)
        }
    };
    exit_code
}

fn kagent_main() -> i32 {
    match KAgentCli::try_parse() {
        Ok(cli) => match run_kagent(cli) {
            Ok(code) => code,
            Err(error) => {
                let _ = print_kagent_error(&error);
                error_exit_code(&error)
            }
        },
        Err(error) if !error.use_stderr() => {
            let _ = error.print();
            0
        }
        Err(error) => {
            let error = CliError::from_clap(error);
            let _ = print_kagent_error(&error);
            error_exit_code(&error)
        }
    }
}

fn invoked_as_kagent() -> bool {
    Path::new(&current_executable_argument())
        .file_stem()
        .is_some_and(|name| name == "kagent")
}

#[derive(Debug, Serialize)]
struct KAgentError<'a> {
    error: &'a str,
    message: &'a str,
    next: &'static str,
}

fn print_kagent_error(error: &CliError) -> Result<()> {
    output::print_structured(
        &KAgentError {
            error: error.code,
            message: &error.message,
            next: "Run `kagent --help` for valid commands and arguments.",
        },
        OutputFormat::Toon,
    )
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct KAgentHome {
    bin: String,
    description: &'static str,
    root: String,
    ready: bool,
    runtime: String,
    reference_index_ready: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    limitation: Option<String>,
    next: Vec<String>,
}

fn run_kagent(cli: KAgentCli) -> Result<i32> {
    let Some(command) = cli.command else {
        let root = config::resolve_workspace_root(None)?;
        let home = kagent_home(root)?;
        output::print_structured(&home, OutputFormat::Toon)?;
        return Ok(0);
    };
    match command {
        KAgentCommand::Graph(cli::KAgentGraphArgs {
            command: Some(cli::KAgentGraphCommand::Summary),
        }) => run_kagent_graph_summary(),
        command => Err(CliError::new(
            "KAGENT_NOT_IMPLEMENTED",
            format!(
                "`kagent {}` is not implemented yet.",
                kagent_command_name(&command)
            ),
        )),
    }
}

fn run_kagent_graph_summary() -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    let envelope = agent::execute_projected(cli::AgentCommand::Graph(cli::AgentNativeGraphArgs {
        runtime: cli::AgentRuntimeArgs {
            workspace_root: Some(workspace_root),
            ..Default::default()
        },
        database: None,
        scope: None,
        operation: cli::NativeGraphOperation::Summary,
        file_paths: Vec::new(),
        removed_file_paths: Vec::new(),
        modules: Vec::new(),
        source_sets: Vec::new(),
        exclusive: false,
        symbol: None,
        generation: None,
        after_id: None,
        limit: None,
        resolution: None,
    }));
    if !envelope.ok {
        let error = envelope.error.ok_or_else(|| {
            CliError::new(
                "KAGENT_INVALID_AGENT_RESULT",
                "The graph operation failed without an actionable error.",
            )
        })?;
        output::print_structured(
            &KAgentError {
                error: &error.code,
                message: &error.message,
                next: "Run `kagent --help` for valid commands and arguments.",
            },
            OutputFormat::Toon,
        )?;
        return Ok(1);
    }
    let result = envelope.result.ok_or_else(|| {
        CliError::new(
            "KAGENT_INVALID_AGENT_RESULT",
            "The graph operation completed without a result.",
        )
    })?;
    output::print_structured(&sanitize_kagent_result(result, true), OutputFormat::Toon)?;
    Ok(0)
}

fn sanitize_kagent_result(value: serde_json::Value, root: bool) -> serde_json::Value {
    match value {
        serde_json::Value::Object(fields) => serde_json::Value::Object(
            fields
                .into_iter()
                .filter_map(|(key, value)| {
                    let protocol_cruft = matches!(key.as_str(), "ok" | "method" | "schemaVersion");
                    (!(protocol_cruft || root && key == "type"))
                        .then(|| (key, sanitize_kagent_result(value, false)))
                })
                .collect(),
        ),
        serde_json::Value::Array(items) => serde_json::Value::Array(
            items
                .into_iter()
                .map(|item| sanitize_kagent_result(item, false))
                .collect(),
        ),
        scalar => scalar,
    }
}

fn kagent_home(root: PathBuf) -> Result<KAgentHome> {
    let readiness = self_mgmt::doctor(cli::ReadyTarget::Agent, Some(&root))?;
    let mut runtime_state = "DOWN".to_string();
    let mut reference_index_ready = false;
    let mut limitation = readiness.issues.first().cloned();
    if readiness.ok {
        match runtime::workspace_status(cli::RuntimeArgs {
            workspace_root: Some(root.clone()),
            ..default_runtime_args()
        }) {
            Ok(status) => {
                if let Some(selected) = status.selected {
                    reference_index_ready = selected
                        .runtime_status
                        .as_ref()
                        .is_some_and(|runtime| runtime.reference_index_ready);
                    runtime_state = selected
                        .runtime_status
                        .as_ref()
                        .map(|runtime| format!("{:?}", runtime.state).to_uppercase())
                        .unwrap_or_else(|| "UNREACHABLE".to_string());
                    limitation = selected.error_message;
                }
            }
            Err(error) => {
                runtime_state = "BLOCKED".to_string();
                limitation = Some(error.message);
            }
        }
    }
    let ready = runtime_state == "READY" && reference_index_ready;
    let next = if ready {
        vec![
            "kagent refresh".to_string(),
            "kagent symbol find <query>".to_string(),
        ]
    } else {
        vec!["kagent up".to_string()]
    };
    Ok(KAgentHome {
        bin: display_invoked_executable(),
        description: "Compiler-backed Kotlin knowledge and changes for coding agents.",
        root: root.display().to_string(),
        ready,
        runtime: runtime_state,
        reference_index_ready,
        limitation,
        next,
    })
}

fn kagent_command_name(command: &KAgentCommand) -> &'static str {
    match command {
        KAgentCommand::Up => "up",
        KAgentCommand::Refresh(_) => "refresh",
        KAgentCommand::Files { .. } => "files",
        KAgentCommand::Symbol(_) => "symbol",
        KAgentCommand::Graph(_) => "graph",
        KAgentCommand::Check(_) => "check",
        KAgentCommand::Change(_) => "change",
        KAgentCommand::Apply { .. } => "apply",
    }
}

fn display_invoked_executable() -> String {
    let raw = current_executable_argument();
    let home = env::var_os("HOME")
        .map(PathBuf::from)
        .map(|path| path.display().to_string());
    if let Some(home) = home
        && let Some(stripped) = raw.strip_prefix(&home)
    {
        return format!("~{stripped}");
    }
    raw
}

fn parse_cli() -> Result<Option<Cli>> {
    match Cli::try_parse() {
        Ok(cli) => Ok(Some(cli)),
        Err(error) if !error.use_stderr() => {
            error.print()?;
            Ok(None)
        }
        Err(error) => Err(CliError::from_clap(error)),
    }
}

fn requested_output_format() -> OutputFormat {
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        if arg == "--output" {
            return match args.next().as_deref() {
                Some("json") => OutputFormat::Json,
                Some("toon") => OutputFormat::Toon,
                _ => OutputFormat::Human,
            };
        }
        if let Some(value) = arg.strip_prefix("--output=") {
            return match value {
                "json" => OutputFormat::Json,
                "toon" => OutputFormat::Toon,
                _ => OutputFormat::Human,
            };
        }
    }
    implicit_output_format()
}

fn effective_output_format(
    requested: Option<OutputFormat>,
    command: Option<&Command>,
) -> OutputFormat {
    if let Some(requested) = requested {
        return requested;
    }
    if matches!(command, Some(Command::Agent(_) | Command::Config(_))) {
        return OutputFormat::Toon;
    }
    implicit_output_format()
}

fn warn_for_deprecated_agent_json(cli: &Cli) {
    if cli.output == Some(OutputFormat::Json) && matches!(cli.command, Some(Command::Agent(_))) {
        eprintln!("{AGENT_JSON_DEPRECATION_WARNING}");
    }
}

fn implicit_output_format() -> OutputFormat {
    if dynamic_output_enabled() && OutputEnvironment::current().allows_human_output() {
        OutputFormat::Human
    } else {
        OutputFormat::Toon
    }
}

fn error_exit_code(error: &CliError) -> i32 {
    if error.code == "CLI_USAGE" { 2 } else { 1 }
}

fn dynamic_output_enabled() -> bool {
    config::KastConfig::load_global()
        .map(|config| config.cli.dynamic_output)
        .unwrap_or(true)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct OutputEnvironment {
    stdin_terminal: bool,
    stdout_terminal: bool,
    ci: bool,
    dumb_terminal: bool,
    agent_process: bool,
}

impl OutputEnvironment {
    fn current() -> Self {
        Self {
            stdin_terminal: io::stdin().is_terminal(),
            stdout_terminal: io::stdout().is_terminal(),
            ci: env_flag("CI"),
            dumb_terminal: env::var("TERM").is_ok_and(|term| term.eq_ignore_ascii_case("dumb")),
            agent_process: agent_process_environment_present(),
        }
    }

    fn allows_human_output(self) -> bool {
        self.stdin_terminal
            && self.stdout_terminal
            && !self.ci
            && !self.dumb_terminal
            && !self.agent_process
    }
}

fn env_flag(name: &str) -> bool {
    env::var(name)
        .ok()
        .is_some_and(|value| !value.trim().is_empty() && value != "0")
}

fn agent_process_environment_present() -> bool {
    const AGENT_PROCESS_ENV_KEYS: &[&str] = &[
        "CODEX_SANDBOX",
        "CODEX_SESSION_ID",
        "CODEX_TASK_ID",
        "CODEX_RUN_ID",
        "CLAUDECODE",
        "CLAUDE_CODE_ENTRYPOINT",
        "CLAUDE_CODE_SSE_PORT",
        "OPENCODE",
        "OPENCODE_SESSION",
        "CURSOR_AGENT",
        "GITHUB_COPILOT_AGENT",
    ];
    AGENT_PROCESS_ENV_KEYS.iter().any(|key| env_flag(key))
}

fn default_runtime_args() -> cli::RuntimeArgs {
    cli::RuntimeArgs {
        workspace_root: None,
        backend_name: None,
        idea_home: None,
        wait_timeout_ms: cli::DEFAULT_RUNTIME_WAIT_TIMEOUT_MS,
        accept_indexing: None,
        no_auto_start: None,
        socket_path: None,
        module_name: None,
        source_roots: None,
        classpath: None,
        request_timeout_ms: None,
        max_results: None,
        max_concurrent_requests: None,
        profile: false,
        profile_modes: None,
        profile_duration: None,
        profile_otlp_endpoint: None,
    }
}

include!("interface/entrypoint/dispatch.rs");
include!("interface/entrypoint/tests.rs");
