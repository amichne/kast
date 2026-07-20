#![cfg_attr(target_os = "macos", allow(dead_code))]

mod agent;
mod bundle;
mod catalog_schema;
mod cli;
mod codex;
mod config;
mod contract_gen;
mod daemon;
mod demo;
mod error;
mod install;
mod lsp;
mod machine;
mod manifest;
mod metrics;
mod metrics_database;
mod output;
mod package;
mod rpc;
mod runtime;
mod self_mgmt;
mod source_index_db;
mod source_index_schema;
mod symbol_query;
mod symbol_query_filters;
mod validate;
mod workspace_inventory;

use clap::{CommandFactory, Parser};
use cli::{Cli, Command, GenerateCommand, OutputFormat, ShellKind};
use error::{CliError, Result};
use serde::Serialize;
use std::env;
use std::io::{self, IsTerminal};
use std::path::{Path, PathBuf};

const SCHEMA_VERSION: u32 = 3;
const AGENT_JSON_DEPRECATION_WARNING: &str =
    "warning: JSON output for `kast agent` is deprecated; omit `--output json` to use TOON.";

fn main() {
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
    std::process::exit(exit_code);
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
    if matches!(command, Some(Command::Agent(_))) {
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

fn run(cli: Cli, output_format: OutputFormat) -> Result<i32> {
    let command = cli
        .command
        .unwrap_or_else(|| Command::Context(default_runtime_args()));
    let _exposure = codex::classify_command(&command);
    match command {
        Command::Help { topic } => {
            if topic.is_empty() {
                Cli::command().print_long_help()?;
                println!();
            } else {
                cli::print_topic_help(&topic)?;
            }
            Ok(0)
        }
        Command::Version => {
            println!("Kast CLI {}", cli::version());
            Ok(0)
        }
        Command::Context(args) => run_context(args, output_format),
        Command::Setup(args) => run_setup(args, output_format),
        Command::Ready(args) => run_ready(args, output_format),
        Command::Repair(args) => run_repair(args, output_format),
        Command::Status(args) => run_runtime(cli::RuntimeCommand::Status(args), output_format),
        Command::Machine(args) => run_machine(args.command, output_format),
        Command::Demo(args) => demo::run_public(args, output_format),
        Command::Developer(args) => run_developer(args.command, output_format),
        Command::Doctor(args) => run_ready(args.into(), output_format),
        Command::Agent(args) => run_agent(args, output_format),
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ContextCommandHint {
    command: String,
    purpose: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct KastContext {
    #[serde(rename = "type")]
    context_type: &'static str,
    bin: String,
    description: &'static str,
    workspace_root: String,
    output_default: &'static str,
    commands: Vec<ContextCommandHint>,
    help: Vec<String>,
    schema_version: u32,
}

fn run_context(args: cli::RuntimeArgs, output_format: OutputFormat) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(args.workspace_root)?;
    let context = KastContext {
        context_type: "KAST_CONTEXT",
        bin: display_current_executable(),
        description: "Compiler-backed Kotlin semantic navigation, editing, diagnostics, and repository agent setup.",
        workspace_root: workspace_root.display().to_string(),
        output_default: "Kast agent commands always default to TOON; JSON remains deprecated compatibility output.",
        commands: context_command_hints(),
        help: vec![
            "Run `kast --help` for command reference.".to_string(),
            "Run `kast repair --apply` only when readiness output asks for install-state repair."
                .to_string(),
        ],
        schema_version: SCHEMA_VERSION,
    };
    if output_format.is_structured() {
        output::print_structured(&context, output_format)?;
    } else {
        print_context_human(&context)?;
    }
    Ok(0)
}

fn context_command_hints() -> Vec<ContextCommandHint> {
    #[cfg(target_os = "macos")]
    {
        vec![
            ContextCommandHint {
                command: "kast repair --for machine --apply".to_string(),
                purpose: "Repair the CLI-only Homebrew receipt and recognized legacy state."
                    .to_string(),
            },
            ContextCommandHint {
                command: "kast agent verify --workspace-root <repo>".to_string(),
                purpose:
                    "Check backend health, runtime state, and capabilities after IDE activation."
                        .to_string(),
            },
            ContextCommandHint {
                command: "kast agent symbol --query <name> --workspace-root <repo>".to_string(),
                purpose: "Resolve Kotlin symbol identity before reading or editing.".to_string(),
            },
        ]
    }
    #[cfg(not(target_os = "macos"))]
    {
        vec![
            ContextCommandHint {
                command: "kast setup --workspace-root <repo>".to_string(),
                purpose: "Install or repair the Kast skill and managed repo instructions."
                    .to_string(),
            },
            ContextCommandHint {
                command: "kast agent verify --workspace-root <repo>".to_string(),
                purpose: "Check backend health, runtime state, and capabilities.".to_string(),
            },
            ContextCommandHint {
                command: "kast agent symbol --query <name> --workspace-root <repo>".to_string(),
                purpose: "Resolve Kotlin symbol identity before reading or editing.".to_string(),
            },
        ]
    }
}

fn print_context_human(context: &KastContext) -> Result<()> {
    let mut markdown = String::new();
    markdown.push_str("# Kast context\n\n");
    markdown.push_str(&format!("- Bin: `{}`\n", context.bin));
    markdown.push_str(&format!("- Description: {}\n", context.description));
    markdown.push_str(&format!("- Workspace: `{}`\n", context.workspace_root));
    markdown.push_str(&format!("- Output: {}\n\n", context.output_default));
    markdown.push_str("## Commands\n");
    for command in &context.commands {
        markdown.push_str(&format!("- `{}`: {}\n", command.command, command.purpose));
    }
    markdown.push_str("\n## Help\n");
    for help in &context.help {
        markdown.push_str(&format!("- {help}\n"));
    }
    output::print_markdown(&markdown)
}

fn display_current_executable() -> String {
    let raw = env::current_exe()
        .ok()
        .map(|path| path.display().to_string())
        .unwrap_or_else(current_executable_argument);
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

fn run_setup(args: cli::SetupArgs, output_format: OutputFormat) -> Result<i32> {
    #[cfg(target_os = "macos")]
    {
        let _ = args;
        macos_plugin_bootstrap_required("setup", output_format)
    }
    #[cfg(not(target_os = "macos"))]
    {
        let guidance = setup_to_agent_guidance_args(args);
        run_agent_guidance_setup_with_command(guidance, output_format, root_setup_command)
    }
}

fn setup_to_agent_guidance_args(args: cli::SetupArgs) -> cli::AgentGuidanceSetupArgs {
    cli::AgentGuidanceSetupArgs {
        workspace_root: args.workspace_root,
        skill_target_dir: args.skill_target_dir,
        context_files: args.context_files,
        force: args.force,
        no_auto_exclude_git: args.no_auto_exclude_git,
        dry_run: args.dry_run,
    }
}

fn run_ready(args: cli::ReadyArgs, output_format: OutputFormat) -> Result<i32> {
    let cli::ReadyArgs { runtime, target } = args;
    let workspace_root = runtime
        .workspace_root
        .as_deref()
        .map(|path| config::resolve_workspace_root(Some(path.to_path_buf())))
        .transpose()?;
    let result = self_mgmt::doctor(false, target, workspace_root.as_deref())?;
    if output_format.is_structured() {
        output::print_structured(&result, output_format)?;
    } else {
        output::print_ready(&result)?;
    }
    Ok(if result.ok { 0 } else { 1 })
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepairCommandResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    target: cli::ReadyTarget,
    applied: bool,
    repair: install::InstallRepairResult,
    ready: self_mgmt::SelfDoctorResult,
    ok: bool,
    schema_version: u32,
}

fn run_repair(args: cli::RepairArgs, output_format: OutputFormat) -> Result<i32> {
    let cli::RepairArgs {
        runtime,
        target,
        apply,
        jetbrains_config_root,
    } = args;
    let workspace_root = runtime
        .workspace_root
        .as_deref()
        .map(|path| config::resolve_workspace_root(Some(path.to_path_buf())))
        .transpose()?;
    let repair_args = cli::InstallRepairArgs {
        apply,
        jetbrains_config_root,
    };
    if apply && !install::macos_homebrew_repair_authority_is_provable()? {
        manifest::install_current_executable()?;
    }
    let repair = install::repair_install_state(repair_args)?;
    let ready = self_mgmt::doctor(false, target, workspace_root.as_deref())?;
    let ok = ready.ok;
    let result = RepairCommandResult {
        result_type: "KAST_REPAIR",
        target,
        applied: apply,
        repair,
        ready,
        ok,
        schema_version: SCHEMA_VERSION,
    };
    if output_format.is_structured() {
        output::print_structured(&result, output_format)?;
    } else {
        output::print_structured(&result, OutputFormat::Toon)?;
    }
    Ok(if ok { 0 } else { 1 })
}

fn run_agent(args: cli::AgentArgs, output_format: OutputFormat) -> Result<i32> {
    match args.command {
        None => Err(CliError::new(
            "CLI_USAGE",
            "An agent command is required; run `kast agent --help`.",
        )),
        Some(cli::AgentCommand::Lsp(args)) => lsp::run(args),
        Some(command) => agent::run(command, output_format),
    }
}

fn current_executable_argument() -> String {
    env::args_os()
        .next()
        .map(|arg| arg.to_string_lossy().into_owned())
        .filter(|arg| !arg.is_empty())
        .unwrap_or_else(|| "kast".to_string())
}

#[cfg(target_os = "macos")]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RemovedOperatorCommandEnvelope<'a> {
    ok: bool,
    method: &'a str,
    error: RemovedOperatorCommandError<'a>,
    schema_version: u32,
}

#[cfg(target_os = "macos")]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RemovedOperatorCommandError<'a> {
    code: &'static str,
    message: &'a str,
    details: RemovedOperatorCommandDetails<'a>,
}

#[cfg(target_os = "macos")]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RemovedOperatorCommandDetails<'a> {
    replacements: &'a [&'a str],
}

#[cfg(target_os = "macos")]
fn removed_operator_command(
    method: &'static str,
    message: &'static str,
    replacements: &'static [&'static str],
    output_format: OutputFormat,
) -> Result<i32> {
    output::print_structured(
        &RemovedOperatorCommandEnvelope {
            ok: false,
            method,
            error: RemovedOperatorCommandError {
                code: "AGENT_COMMAND_REMOVED",
                message,
                details: RemovedOperatorCommandDetails { replacements },
            },
            schema_version: SCHEMA_VERSION,
        },
        output_format,
    )?;
    Ok(1)
}

#[cfg(target_os = "macos")]
fn macos_plugin_bootstrap_required(
    method: &'static str,
    output_format: OutputFormat,
) -> Result<i32> {
    removed_operator_command(
        method,
        "macOS workspace setup is owned by the JetBrains-installed Kast plugin. The CLI does not write plugin directories, runtime state, or skill-only workspace state on macOS.",
        &[
            "/bin/bash -c \"$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)\" -- install",
            "Add https://github.com/amichne/kast/releases/latest/download/updatePlugins.xml as a custom plugin repository",
            "Open the workspace in IntelliJ IDEA or Android Studio with the Kast plugin enabled",
            "kast agent verify --workspace-root <repo>",
        ],
        output_format,
    )
}

fn run_agent_guidance_setup_with_command(
    args: cli::AgentGuidanceSetupArgs,
    output_format: OutputFormat,
    command_builder: fn(&cli::AgentGuidanceSetupArgs) -> Vec<String>,
) -> Result<i32> {
    let install_command = command_builder(&args);
    if args.dry_run {
        let plan = install::agent_guidance_setup_plan(&args, install_command)?;
        if output_format.is_structured() {
            output::print_structured(&plan, output_format)?;
        } else {
            output::print_agent_guidance_setup_plan(&plan)?;
        }
        return Ok(0);
    }
    let result = install::install_agent_guidance(args, install_command)?;
    if output_format.is_structured() {
        output::print_structured(&result, output_format)?;
    } else {
        output::print_agent_guidance_setup_result(&result)?;
    }
    Ok(0)
}

fn root_setup_command(args: &cli::AgentGuidanceSetupArgs) -> Vec<String> {
    let mut command = vec![current_executable_argument(), "setup".to_string()];
    if let Some(workspace_root) = &args.workspace_root {
        command.push("--workspace-root".to_string());
        command.push(workspace_root.display().to_string());
    }
    if let Some(skill_target_dir) = &args.skill_target_dir {
        command.push("--skill-target-dir".to_string());
        command.push(skill_target_dir.display().to_string());
    }
    for target in &args.context_files {
        command.push("--context-file".to_string());
        command.push(target.display().to_string());
    }
    if args.force {
        command.push("--force".to_string());
    }
    if args.no_auto_exclude_git {
        command.push("--no-auto-exclude-git".to_string());
    }
    command
}

fn run_runtime(command: cli::RuntimeCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::RuntimeCommand::Up(args) => {
            let result = runtime::workspace_ensure(args)?;
            if output_format.is_structured() {
                output::print_structured(&result, output_format)?;
            } else {
                output::print_workspace_ensure(&result)?;
            }
            Ok(0)
        }
        cli::RuntimeCommand::Status(args) => {
            let result = runtime::workspace_status(args)?;
            if output_format.is_structured() {
                output::print_structured(&result, output_format)?;
            } else {
                output::print_workspace_status(&result)?;
            }
            Ok(0)
        }
        cli::RuntimeCommand::Stop(args) => {
            let result = runtime::workspace_stop(args)?;
            if output_format.is_structured() {
                output::print_structured(&result, output_format)?;
            } else {
                output::print_stop_result(&result)?;
            }
            Ok(0)
        }
        cli::RuntimeCommand::Restart(args) => {
            let result = runtime::workspace_restart(args)?;
            if output_format.is_structured() {
                output::print_structured(&result, output_format)?;
            } else {
                output::print_restart_result(&result)?;
            }
            Ok(0)
        }
        cli::RuntimeCommand::Capabilities(args) => {
            let result = runtime::capabilities(args)?;
            if output_format.is_structured() {
                output::print_structured(&result, output_format)?;
            } else {
                output::print_capabilities(&result)?;
            }
            Ok(0)
        }
    }
}

fn run_developer(command: cli::DeveloperCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::DeveloperCommand::Runtime(args) => run_runtime(args.command, output_format),
        cli::DeveloperCommand::Inspect(args) => run_inspect(args.command, output_format),
        cli::DeveloperCommand::Machine(args) => run_machine(args.command, output_format),
        cli::DeveloperCommand::Release(args) => run_release(args.command, output_format),
        cli::DeveloperCommand::Codex(args) => codex::run(args.command, output_format),
    }
}

fn run_inspect(command: cli::InspectCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::InspectCommand::Paths(args) => run_paths(args, output_format),
        cli::InspectCommand::Metrics { command } => metrics::run(command, output_format),
        cli::InspectCommand::Demo(args) => {
            let _ = args;
            let mut error = CliError::new(
                "DEMO_COMMAND_MOVED",
                "`kast developer inspect demo` moved to the public `kast demo` experience.",
            );
            error.details.insert(
                "replacement".to_string(),
                "kast demo --workspace-root <repo>".to_string(),
            );
            Err(error)
        }
        cli::InspectCommand::Catalog(args) => run_validate(args),
    }
}

fn run_machine(command: cli::MachineCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::MachineCommand::Status => {
            let result = machine::status()?;
            if output_format.is_structured() {
                output::print_structured(&result, output_format)?;
            } else {
                println!("Kast machine\n\nState: {}", result.state);
            }
            Ok(0)
        }
        cli::MachineCommand::Activate(args) => {
            let result = machine::activate(args)?;
            if output_format.is_structured() {
                output::print_structured(&result, output_format)?;
            } else {
                println!("Kast machine\n\nState: activated\nCLI: {}", result.cli);
            }
            Ok(0)
        }
        cli::MachineCommand::Reconcile(args) => {
            let result = machine::reconcile(args)?;
            if output_format.is_structured() {
                output::print_structured(&result, output_format)?;
            } else {
                println!(
                    "Kast machine\n\nState: reconciled\nIDEA plugin: {}\nSkill: {}\nCodex: {}",
                    result.idea_plugin,
                    result.skill,
                    result.codex.as_deref().unwrap_or("not installed"),
                );
            }
            Ok(0)
        }
        cli::MachineCommand::Defaults(args) => {
            let result = self_mgmt::configure_developer_machine_defaults(args.dry_run)?;
            if output_format.is_structured() {
                output::print_structured(&result, output_format)?;
            } else {
                output::print_developer_machine_defaults(&result)?;
            }
            Ok(0)
        }
        cli::MachineCommand::Shell(args) => {
            run_install(cli::InstallCommand::Shell(args), output_format)
        }
        cli::MachineCommand::Completion(args) => {
            print_completion(args);
            Ok(0)
        }
    }
}

fn run_release(command: cli::ReleaseCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::ReleaseCommand::Package(args) => run_package(args, output_format),
        cli::ReleaseCommand::Activate(args) => match args.command {
            cli::ReleaseActivateCommand::Bundle(args) => {
                run_install(cli::InstallCommand::ActivateBundle(args), output_format)
            }
        },
        cli::ReleaseCommand::Generate(args) => run_generate(args),
        cli::ReleaseCommand::Validate(args) => run_validate(args),
    }
}

fn run_validate(args: cli::ValidateArgs) -> Result<i32> {
    let result = validate::run(args)?;
    output::print_json(&result)?;
    Ok(if result.ok { 0 } else { 1 })
}

fn run_generate(args: cli::GenerateArgs) -> Result<i32> {
    match args.command {
        GenerateCommand::Contract(args) => {
            let paths = contract_paths(&args);
            let result = if args.check {
                contract_gen::check(&paths)?
            } else {
                contract_gen::write(&paths)?
            };
            output::print_json(&result)?;
            Ok(0)
        }
    }
}

fn run_package(args: cli::PackageArgs, output_format: OutputFormat) -> Result<i32> {
    let result = package::run(args)?;
    if output_format.is_structured() {
        output::print_structured(&result, output_format)?;
    } else {
        output::print_package_result(&result)?;
    }
    Ok(0)
}

fn run_paths(args: cli::PathsArgs, output_format: OutputFormat) -> Result<i32> {
    let workspace_root = args
        .workspace_root
        .as_deref()
        .map(|path| config::resolve_workspace_root(Some(path.to_path_buf())))
        .transpose()?;
    let config = match &workspace_root {
        Some(root) => config::KastConfig::load(root)?,
        None => config::KastConfig::load_global()?,
    };
    let mode = if args.idea {
        config::PathResolutionMode::Idea
    } else {
        config::PathResolutionMode::Cli
    };
    let result = config::path_resolution_report(&config, workspace_root.as_deref(), mode)?;
    if output_format.is_structured() {
        output::print_structured(&result, output_format)?;
    } else {
        output::print_paths(&result)?;
    }
    Ok(0)
}

fn run_install(command: cli::InstallCommand, output_format: OutputFormat) -> Result<i32> {
    let command = match command {
        cli::InstallCommand::Completion(completion_args) => {
            print_completion(completion_args);
            return Ok(0);
        }
        command => command,
    };
    let result = install::install(cli::InstallArgs { command })?;
    if output_format.is_structured() {
        output::print_structured(&result, output_format)?;
    } else {
        output::print_install_result(&result)?;
    }
    Ok(0)
}

fn contract_paths(args: &cli::GenerateContractArgs) -> contract_gen::ContractPaths {
    let mut paths = contract_gen::ContractPaths::defaults(Path::new(env!("CARGO_MANIFEST_DIR")));
    if let Some(catalog) = &args.catalog {
        paths.catalog = catalog.clone();
    }
    if let Some(yaml) = &args.yaml {
        paths.yaml = yaml.clone();
    }
    if let Some(samples_root) = &args.samples_root {
        paths.samples_root = samples_root.clone();
    }
    paths
}

fn print_completion(args: cli::CompletionArgs) {
    let mut command = Cli::command();
    let command_name = args.command_name.unwrap_or_else(|| "kast".to_string());
    clap_complete::generate(
        completion_shell(args.shell),
        &mut command,
        command_name,
        &mut io::stdout(),
    );
}

fn completion_shell(shell: ShellKind) -> clap_complete::Shell {
    match shell {
        ShellKind::Bash => clap_complete::Shell::Bash,
        ShellKind::Zsh => clap_complete::Shell::Zsh,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn interactive_human_environment() -> OutputEnvironment {
        OutputEnvironment {
            stdin_terminal: true,
            stdout_terminal: true,
            ci: false,
            dumb_terminal: false,
            agent_process: false,
        }
    }

    #[test]
    fn output_environment_allows_human_only_for_interactive_non_agent_terminal() {
        assert!(interactive_human_environment().allows_human_output());

        for environment in [
            OutputEnvironment {
                stdin_terminal: false,
                ..interactive_human_environment()
            },
            OutputEnvironment {
                stdout_terminal: false,
                ..interactive_human_environment()
            },
            OutputEnvironment {
                ci: true,
                ..interactive_human_environment()
            },
            OutputEnvironment {
                dumb_terminal: true,
                ..interactive_human_environment()
            },
            OutputEnvironment {
                agent_process: true,
                ..interactive_human_environment()
            },
        ] {
            assert!(!environment.allows_human_output(), "{environment:?}");
        }
    }

    #[test]
    fn agent_commands_default_to_toon_even_in_an_interactive_terminal() {
        let cli = Cli::try_parse_from(["kast", "agent"]).expect("parse agent home");

        assert_eq!(
            effective_output_format(None, cli.command.as_ref()),
            OutputFormat::Toon
        );
        assert_eq!(
            effective_output_format(Some(OutputFormat::Json), cli.command.as_ref()),
            OutputFormat::Json
        );
    }
}
