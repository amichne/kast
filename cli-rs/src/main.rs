mod agent;
mod bundle;
mod catalog_schema;
mod cli;
mod config;
mod contract_gen;
mod daemon;
mod demo;
mod error;
mod install;
mod lsp;
mod manifest;
mod metrics;
mod metrics_database;
mod orchestration;
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

use clap::{CommandFactory, Parser};
use cli::{Cli, Command, GenerateCommand, OutputFormat, ShellKind};
use error::{CliError, Result};
use std::env;
use std::io;
use std::path::{Path, PathBuf};

const SCHEMA_VERSION: u32 = 3;

fn main() {
    let exit_code = match parse_cli() {
        Ok(Some(cli)) => {
            let output_format = cli.output;
            match run(cli) {
                Ok(code) => code,
                Err(error) => {
                    let _ = output::print_error(&error, output_format);
                    1
                }
            }
        }
        Ok(None) => 0,
        Err(error) => {
            let _ = output::print_error(&error, requested_output_format());
            1
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
            if args.next().as_deref() == Some("json") {
                return OutputFormat::Json;
            }
            continue;
        }
        if arg == "--output=json" {
            return OutputFormat::Json;
        }
    }
    OutputFormat::Human
}

fn run(cli: Cli) -> Result<i32> {
    let output_format = cli.output;
    let command = cli.command.unwrap_or(Command::Help { topic: vec![] });
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
        Command::Rpc(args) => {
            let response = runtime::rpc_passthrough(args)?;
            println!("{response}");
            Ok(0)
        }
        Command::Ready(args) => run_ready(args, output_format),
        Command::Agent(args) => run_agent(args, output_format),
        Command::Runtime(args) => run_runtime(args.command, output_format),
        Command::Inspect(args) => run_inspect(args.command, output_format),
        Command::Machine(args) => run_machine(args.command, output_format),
        Command::Release(args) => run_release(args.command, output_format),
    }
}

fn run_ready(args: cli::ReadyArgs, output_format: OutputFormat) -> Result<i32> {
    let result = self_mgmt::doctor(args.fix, args.target)?;
    if output_format == OutputFormat::Json {
        output::print_json(&result)?;
    } else {
        output::print_ready(&result)?;
    }
    Ok(if result.ok { 0 } else { 1 })
}

fn run_agent(args: cli::AgentArgs, output_format: OutputFormat) -> Result<i32> {
    match args.command {
        cli::AgentCommand::Up(args) => run_agent_up(args, output_format),
        cli::AgentCommand::Ready(args) => run_ready(args, output_format),
        cli::AgentCommand::Setup(args) => run_agent_setup(args.command, output_format),
        cli::AgentCommand::Lsp(args) => lsp::run(args),
        command => agent::run(cli::AgentArgs { command }),
    }
}

fn run_agent_up(args: cli::AgentUpArgs, output_format: OutputFormat) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(args.runtime.workspace_root.clone())?;
    let selection_args = agent_up_selection_args(&args);
    let selection = agent_setup_auto_selection(&workspace_root, &selection_args)?;
    let setup_args = agent_up_setup_args(args.clone(), &selection, &workspace_root);
    let setup_plan = agent_setup_auto_plan(&selection, &setup_args);
    let runtime_args = agent_up_runtime_args(args.runtime, &workspace_root);
    let runtime_command = agent_up_runtime_command(&runtime_args);
    if args.dry_run {
        let result = orchestration::AgentUpResult::dry_run(setup_plan, runtime_command);
        print_agent_up_result(&result, output_format)?;
        return Ok(0);
    }

    let install = match install::install(
        cli::InstallArgs {
            command: agent_setup_command_for_harness(selection.harness, setup_args),
        },
        &mut install::NoopInstallReporter,
    ) {
        Ok(install) => install,
        Err(error) => {
            let result = orchestration::AgentUpResult::failure(
                setup_plan,
                None,
                None,
                runtime_command,
                error,
            );
            print_agent_up_result(&result, output_format)?;
            return Ok(1);
        }
    };
    let runtime = match runtime::workspace_ensure(runtime_args) {
        Ok(runtime) => runtime,
        Err(error) => {
            let result = orchestration::AgentUpResult::failure(
                setup_plan,
                Some(install),
                None,
                runtime_command,
                error,
            );
            print_agent_up_result(&result, output_format)?;
            return Ok(1);
        }
    };
    let result =
        orchestration::AgentUpResult::success(setup_plan, install, runtime, runtime_command);
    print_agent_up_result(&result, output_format)?;
    Ok(0)
}

fn print_agent_up_result(
    result: &orchestration::AgentUpResult,
    output_format: OutputFormat,
) -> Result<()> {
    if output_format == OutputFormat::Json {
        output::print_json(result)
    } else {
        output::print_agent_up_result(result)
    }
}

fn agent_up_selection_args(args: &cli::AgentUpArgs) -> cli::AgentSetupAutoArgs {
    cli::AgentSetupAutoArgs {
        harness: args.harness,
        target_dir: args.target_dir.clone(),
        force: args.force,
        no_auto_exclude_git: args.no_auto_exclude_git,
        dry_run: args.dry_run,
    }
}

fn agent_up_setup_args(
    args: cli::AgentUpArgs,
    selection: &AgentSetupAutoSelection,
    workspace_root: &Path,
) -> cli::AgentSetupAutoArgs {
    cli::AgentSetupAutoArgs {
        harness: args.harness,
        target_dir: Some(
            args.target_dir
                .unwrap_or_else(|| default_agent_up_target_dir(selection.harness, workspace_root)),
        ),
        force: args.force,
        no_auto_exclude_git: args.no_auto_exclude_git,
        dry_run: args.dry_run,
    }
}

fn default_agent_up_target_dir(harness: cli::AgentSetupHarness, workspace_root: &Path) -> PathBuf {
    match harness {
        cli::AgentSetupHarness::Auto => unreachable!("auto harness must be resolved"),
        cli::AgentSetupHarness::Copilot => workspace_root.join(".github"),
        cli::AgentSetupHarness::Skill => first_existing_or_default(
            workspace_root,
            &[".agents/skills", ".github/skills", ".claude/skills"],
            ".agents/skills",
        ),
        cli::AgentSetupHarness::Instructions => first_existing_or_default(
            workspace_root,
            &[
                ".agents/instructions",
                ".github/instructions",
                ".claude/instructions",
            ],
            ".agents/instructions",
        ),
    }
}

fn first_existing_or_default(workspace_root: &Path, candidates: &[&str], default: &str) -> PathBuf {
    candidates
        .iter()
        .map(|candidate| workspace_root.join(candidate))
        .find(|candidate| candidate.is_dir())
        .unwrap_or_else(|| workspace_root.join(default))
}

fn agent_up_runtime_args(mut args: cli::RuntimeArgs, workspace_root: &Path) -> cli::RuntimeArgs {
    args.workspace_root = Some(workspace_root.to_path_buf());
    args
}

fn agent_up_runtime_command(args: &cli::RuntimeArgs) -> Vec<String> {
    let mut command = vec!["kast".to_string(), "runtime".to_string(), "up".to_string()];
    if let Some(workspace_root) = &args.workspace_root {
        command.push("--workspace-root".to_string());
        command.push(workspace_root.display().to_string());
    }
    if let Some(backend) = args.backend_name {
        command.push("--backend".to_string());
        command.push(backend.canonical().to_string());
    }
    command
}

fn run_agent_setup(command: cli::AgentSetupCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::AgentSetupCommand::Auto(args) => run_agent_setup_auto(args, output_format),
        cli::AgentSetupCommand::Copilot(args) => {
            run_install(cli::InstallCommand::Copilot(args), output_format)
        }
        cli::AgentSetupCommand::Skill(args) => {
            run_install(cli::InstallCommand::Skill(args), output_format)
        }
        cli::AgentSetupCommand::Instructions(args) => {
            run_install(cli::InstallCommand::Instructions(args), output_format)
        }
    }
}

fn run_agent_setup_auto(args: cli::AgentSetupAutoArgs, output_format: OutputFormat) -> Result<i32> {
    let cwd = env::current_dir().unwrap_or_else(|_| ".".into());
    let selection = agent_setup_auto_selection(&cwd, &args)?;
    if args.dry_run {
        let plan = agent_setup_auto_plan(&selection, &args);
        if output_format == OutputFormat::Json {
            output::print_json(&plan)?;
        } else {
            output::print_agent_setup_auto_plan(&plan)?;
        }
        return Ok(0);
    }

    run_install(
        agent_setup_command_for_harness(selection.harness, args),
        output_format,
    )
}

#[derive(Debug, Clone)]
struct AgentSetupAutoSelection {
    harness: cli::AgentSetupHarness,
    source: install::AgentSetupSelectionSource,
    reason: String,
}

fn agent_setup_auto_selection(
    cwd: &Path,
    args: &cli::AgentSetupAutoArgs,
) -> Result<AgentSetupAutoSelection> {
    match args.harness {
        Some(cli::AgentSetupHarness::Auto) => Ok(agent_setup_auto_detected_harness(cwd, args)),
        Some(harness) => Ok(AgentSetupAutoSelection {
            harness,
            source: install::AgentSetupSelectionSource::Explicit,
            reason: "`--harness` selected the agent resource package.".to_string(),
        }),
        None => {
            let configured = config::KastConfig::load(cwd)?.project_open.agent_harness;
            if configured.is_auto() {
                Ok(agent_setup_auto_detected_harness(cwd, args))
            } else {
                Ok(AgentSetupAutoSelection {
                    harness: configured,
                    source: install::AgentSetupSelectionSource::Config,
                    reason: "`projectOpen.agentHarness` selected the agent resource package."
                        .to_string(),
                })
            }
        }
    }
}

fn agent_setup_auto_detected_harness(
    cwd: &Path,
    args: &cli::AgentSetupAutoArgs,
) -> AgentSetupAutoSelection {
    if let Some(target_dir) = &args.target_dir {
        let (harness, reason) = match target_dir.file_name().and_then(|name| name.to_str()) {
            Some("skills") => (
                cli::AgentSetupHarness::Skill,
                "`--target-dir` ends in `skills`.",
            ),
            Some("instructions") => (
                cli::AgentSetupHarness::Instructions,
                "`--target-dir` ends in `instructions`.",
            ),
            _ => (
                cli::AgentSetupHarness::Copilot,
                "`--target-dir` was provided and no explicit harness was selected.",
            ),
        };
        return AgentSetupAutoSelection {
            harness,
            source: install::AgentSetupSelectionSource::TargetDirectory,
            reason: reason.to_string(),
        };
    }

    let has_skill_root = [".agents/skills", ".github/skills", ".claude/skills"]
        .iter()
        .any(|candidate| cwd.join(candidate).is_dir());
    let has_instruction_root = [
        ".agents/instructions",
        ".github/instructions",
        ".claude/instructions",
    ]
    .iter()
    .any(|candidate| cwd.join(candidate).is_dir());

    if cwd.join(".github").is_dir() || !has_skill_root && !has_instruction_root {
        AgentSetupAutoSelection {
            harness: cli::AgentSetupHarness::Copilot,
            source: install::AgentSetupSelectionSource::Repository,
            reason: "Repository detection found `.github` or no skill/instruction roots."
                .to_string(),
        }
    } else if has_skill_root {
        AgentSetupAutoSelection {
            harness: cli::AgentSetupHarness::Skill,
            source: install::AgentSetupSelectionSource::Repository,
            reason: "Repository detection found a skill root.".to_string(),
        }
    } else {
        AgentSetupAutoSelection {
            harness: cli::AgentSetupHarness::Instructions,
            source: install::AgentSetupSelectionSource::Repository,
            reason: "Repository detection found an instruction root.".to_string(),
        }
    }
}

fn agent_setup_auto_plan(
    selection: &AgentSetupAutoSelection,
    args: &cli::AgentSetupAutoArgs,
) -> install::AgentSetupAutoPlan {
    let mut plan = install::AgentSetupAutoPlan::new(
        selection.harness,
        selection.source,
        selection.reason.clone(),
        agent_setup_install_command(selection.harness, args),
        args.target_dir.clone(),
    );
    plan.dry_run = args.dry_run;
    plan
}

fn agent_setup_install_command(
    harness: cli::AgentSetupHarness,
    args: &cli::AgentSetupAutoArgs,
) -> Vec<String> {
    let mut command = vec![
        "kast".to_string(),
        "agent".to_string(),
        "setup".to_string(),
        match harness {
            cli::AgentSetupHarness::Auto => unreachable!("auto harness must be resolved"),
            cli::AgentSetupHarness::Copilot => "copilot",
            cli::AgentSetupHarness::Skill => "skill",
            cli::AgentSetupHarness::Instructions => "instructions",
        }
        .to_string(),
    ];
    if let Some(target_dir) = &args.target_dir {
        command.push("--target-dir".to_string());
        command.push(target_dir.display().to_string());
    }
    if args.force {
        command.push("--force".to_string());
    }
    if args.no_auto_exclude_git {
        command.push("--no-auto-exclude-git".to_string());
    }
    command
}

fn agent_setup_command_for_harness(
    harness: cli::AgentSetupHarness,
    args: cli::AgentSetupAutoArgs,
) -> cli::InstallCommand {
    match harness {
        cli::AgentSetupHarness::Auto => {
            unreachable!("auto harness must be resolved before install")
        }
        cli::AgentSetupHarness::Copilot => cli::InstallCommand::Copilot(cli::CopilotInstallArgs {
            target_dir: args.target_dir,
            force: args.force,
            no_auto_exclude_git: args.no_auto_exclude_git,
        }),
        cli::AgentSetupHarness::Skill => cli::InstallCommand::Skill(cli::ResourceInstallArgs {
            target_dir: args.target_dir,
            name: None,
            force: args.force,
            no_auto_exclude_git: args.no_auto_exclude_git,
        }),
        cli::AgentSetupHarness::Instructions => {
            cli::InstallCommand::Instructions(cli::ResourceInstallArgs {
                target_dir: args.target_dir,
                name: None,
                force: args.force,
                no_auto_exclude_git: args.no_auto_exclude_git,
            })
        }
    }
}

fn run_runtime(command: cli::RuntimeCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::RuntimeCommand::Up(args) => {
            let result = runtime::workspace_ensure(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_workspace_ensure(&result)?;
            }
            Ok(0)
        }
        cli::RuntimeCommand::Status(args) => {
            let result = runtime::workspace_status(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_workspace_status(&result)?;
            }
            Ok(0)
        }
        cli::RuntimeCommand::Stop(args) => {
            let result = runtime::workspace_stop(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_stop_result(&result)?;
            }
            Ok(0)
        }
        cli::RuntimeCommand::Restart(args) => {
            let result = runtime::workspace_restart(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_restart_result(&result)?;
            }
            Ok(0)
        }
        cli::RuntimeCommand::Capabilities(args) => {
            let result = runtime::capabilities(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_capabilities(&result)?;
            }
            Ok(0)
        }
    }
}

fn run_inspect(command: cli::InspectCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::InspectCommand::Paths(args) => run_paths(args, output_format),
        cli::InspectCommand::Metrics { command } => metrics::run(command, output_format),
        cli::InspectCommand::Demo(args) => demo::run(args),
        cli::InspectCommand::Catalog(args) => run_validate(args),
    }
}

fn run_machine(command: cli::MachineCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::MachineCommand::Plugin(args) => {
            run_install(cli::InstallCommand::Plugin(args), output_format)
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
    if output_format == OutputFormat::Json {
        output::print_json(&result)?;
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
    if output_format == OutputFormat::Json {
        output::print_json(&result)?;
    } else {
        output::print_paths(&result)?;
    }
    Ok(0)
}

fn run_install(command: cli::InstallCommand, output_format: OutputFormat) -> Result<i32> {
    if let cli::InstallCommand::Completion(completion_args) = command {
        print_completion(completion_args);
        return Ok(0);
    }
    let mut reporter = install_reporter(output_format);
    let result = install::install(cli::InstallArgs { command }, reporter.as_mut())?;
    if output_format == OutputFormat::Json {
        output::print_json(&result)?;
    } else {
        output::print_install_result(&result)?;
    }
    Ok(0)
}

fn install_reporter(output_format: OutputFormat) -> Box<dyn install::InstallReporter> {
    if output_format == OutputFormat::Human {
        Box::new(install::HumanInstallReporter::new())
    } else {
        Box::new(install::NoopInstallReporter)
    }
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
