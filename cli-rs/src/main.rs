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
mod onboarding;
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
        Command::Setup(args) => run_setup(args, output_format),
        Command::Ready(args) => run_ready(args, output_format),
        Command::Status(args) => run_runtime(cli::RuntimeCommand::Status(args), output_format),
        Command::Developer(args) => run_developer(args.command, output_format),
        Command::Doctor(args) => run_ready(args, output_format),
        Command::Agent(args) => run_agent(args, output_format),
    }
}

fn run_setup(args: cli::SetupArgs, output_format: OutputFormat) -> Result<i32> {
    if !args.dry_run {
        let readiness = self_mgmt::doctor(true, cli::ReadyTarget::Agent)?;
        if !readiness.ok {
            let mut error = CliError::new(
                "SETUP_READY_FAILED",
                "Setup could not repair Kast install readiness.",
            );
            error
                .details
                .insert("issues".to_string(), readiness.issues.join("; "));
            return Err(error);
        }
    }
    run_agent_up_with_surface(
        setup_to_agent_up_args(args),
        output_format,
        AgentUpCommandSurface::RootSetup,
    )
}

fn setup_to_agent_up_args(args: cli::SetupArgs) -> cli::AgentUpArgs {
    cli::AgentUpArgs {
        runtime: args.runtime,
        agents_md: args.agents_md,
        force: args.force,
        no_auto_exclude_git: args.no_auto_exclude_git,
        dry_run: args.dry_run,
        no_onboard: args.no_open_ide,
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
    let agent_format = args.format;
    match args.command {
        cli::AgentCommand::Up(args) => {
            run_agent_up_with_surface(args, output_format, AgentUpCommandSurface::AgentUp)
        }
        cli::AgentCommand::Ready(args) => run_ready(args, output_format),
        cli::AgentCommand::Setup(args) => run_agent_setup(args, output_format),
        cli::AgentCommand::Lsp(args) => lsp::run(args),
        command => agent::run(cli::AgentArgs {
            format: agent_format,
            command,
        }),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AgentUpCommandSurface {
    RootSetup,
    AgentUp,
}

fn run_agent_up_with_surface(
    mut args: cli::AgentUpArgs,
    output_format: OutputFormat,
    surface: AgentUpCommandSurface,
) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(args.runtime.workspace_root.clone())?;
    let onboarding =
        onboarding::maybe_run_agent_up_onboarding(&mut args, output_format, &workspace_root)?;
    let setup_args = agent_up_setup_args(&args, &workspace_root);
    let no_onboard = args.no_onboard;
    let runtime_args = agent_up_runtime_args(args.runtime, &workspace_root);
    let setup_command = match surface {
        AgentUpCommandSurface::RootSetup => {
            root_setup_command(&setup_args, &runtime_args, no_onboard)
        }
        AgentUpCommandSurface::AgentUp => agent_guidance_setup_command(&setup_args),
    };
    let setup_plan = install::agent_guidance_setup_plan(&setup_args, setup_command.clone())?;
    let runtime_command = agent_up_runtime_command(&runtime_args, surface, no_onboard);
    if args.dry_run {
        let result = orchestration::AgentUpResult::dry_run(setup_plan, runtime_command);
        print_agent_up_result(&result, output_format)?;
        return Ok(0);
    }

    let install = match install::install_agent_guidance(setup_args, setup_command) {
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
                Some(install::InstallResult::AgentGuidance(install)),
                None,
                runtime_command,
                error,
            );
            print_agent_up_result(&result, output_format)?;
            return Ok(1);
        }
    };
    let result = orchestration::AgentUpResult::success(
        setup_plan,
        install::InstallResult::AgentGuidance(install),
        runtime,
        runtime_command,
    );
    let result = match onboarding {
        onboarding::AgentUpOnboardingOutcome::Applied => result.with_onboarding_stage(),
        onboarding::AgentUpOnboardingOutcome::Declined => result.with_manual_step(format!(
            "Automatic IDEA onboarding was skipped. To configure IDEA manually, run `kast developer machine plugin`, open `{}` in IntelliJ IDEA or Android Studio, then run `kast setup --workspace-root {} --backend idea`.",
            workspace_root.display(),
            workspace_root.display()
        )),
        onboarding::AgentUpOnboardingOutcome::NotEligible => result,
    };
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

fn agent_up_setup_args(
    args: &cli::AgentUpArgs,
    workspace_root: &Path,
) -> cli::AgentGuidanceSetupArgs {
    cli::AgentGuidanceSetupArgs {
        workspace_root: Some(workspace_root.to_path_buf()),
        agents_md: args.agents_md.clone(),
        force: args.force,
        no_auto_exclude_git: args.no_auto_exclude_git,
        dry_run: args.dry_run,
    }
}

fn current_executable_argument() -> String {
    env::args_os()
        .next()
        .map(|arg| arg.to_string_lossy().into_owned())
        .filter(|arg| !arg.is_empty())
        .unwrap_or_else(|| "kast".to_string())
}

fn agent_up_runtime_args(mut args: cli::RuntimeArgs, workspace_root: &Path) -> cli::RuntimeArgs {
    args.workspace_root = Some(workspace_root.to_path_buf());
    args
}

fn agent_up_runtime_command(
    args: &cli::RuntimeArgs,
    surface: AgentUpCommandSurface,
    no_onboard: bool,
) -> Vec<String> {
    let mut command = match surface {
        AgentUpCommandSurface::RootSetup => {
            vec![current_executable_argument(), "setup".to_string()]
        }
        AgentUpCommandSurface::AgentUp => vec![
            current_executable_argument(),
            "runtime".to_string(),
            "up".to_string(),
        ],
    };
    if let Some(workspace_root) = &args.workspace_root {
        command.push("--workspace-root".to_string());
        command.push(workspace_root.display().to_string());
    }
    if let Some(backend) = args.backend_name {
        command.push("--backend".to_string());
        command.push(backend.canonical().to_string());
    }
    if surface == AgentUpCommandSurface::RootSetup && no_onboard {
        command.push("--no-open-ide".to_string());
    }
    command
}

fn run_agent_setup(args: cli::AgentSetupArgs, output_format: OutputFormat) -> Result<i32> {
    let cli::AgentSetupArgs { command, guidance } = args;
    match command {
        None => run_agent_guidance_setup(guidance, output_format),
        Some(cli::AgentSetupCommand::Auto(mut args)) => {
            args.force |= guidance.force;
            args.no_auto_exclude_git |= guidance.no_auto_exclude_git;
            args.dry_run |= guidance.dry_run;
            run_agent_setup_auto(args, output_format, guidance.workspace_root)
        }
        Some(cli::AgentSetupCommand::Copilot(mut args)) => {
            merge_copilot_guidance(&mut args, &guidance);
            run_install(cli::InstallCommand::Copilot(args), output_format)
        }
        Some(cli::AgentSetupCommand::Skill(mut args)) => {
            merge_resource_guidance(&mut args, &guidance, cli::AgentSetupHarness::Skill);
            run_install(cli::InstallCommand::Skill(args), output_format)
        }
        Some(cli::AgentSetupCommand::Instructions(mut args)) => {
            merge_resource_guidance(&mut args, &guidance, cli::AgentSetupHarness::Instructions);
            run_install(cli::InstallCommand::Instructions(args), output_format)
        }
    }
}

fn merge_resource_guidance(
    args: &mut cli::ResourceInstallArgs,
    guidance: &cli::AgentGuidanceSetupArgs,
    harness: cli::AgentSetupHarness,
) {
    if args.target_dir.is_none()
        && let Some(workspace_root) = &guidance.workspace_root
    {
        args.target_dir = Some(default_agent_up_target_dir(harness, workspace_root));
    }
    args.force |= guidance.force;
    args.no_auto_exclude_git |= guidance.no_auto_exclude_git;
    args.dry_run |= guidance.dry_run;
}

fn merge_copilot_guidance(
    args: &mut cli::CopilotInstallArgs,
    guidance: &cli::AgentGuidanceSetupArgs,
) {
    if args.target_dir.is_none()
        && let Some(workspace_root) = &guidance.workspace_root
    {
        args.target_dir = Some(default_agent_up_target_dir(
            cli::AgentSetupHarness::Copilot,
            workspace_root,
        ));
    }
    args.force |= guidance.force;
    args.no_auto_exclude_git |= guidance.no_auto_exclude_git;
    args.dry_run |= guidance.dry_run;
}

fn run_agent_guidance_setup(
    args: cli::AgentGuidanceSetupArgs,
    output_format: OutputFormat,
) -> Result<i32> {
    let install_command = agent_guidance_setup_command(&args);
    if args.dry_run {
        let plan = install::agent_guidance_setup_plan(&args, install_command)?;
        if output_format == OutputFormat::Json {
            output::print_json(&plan)?;
        } else {
            output::print_agent_guidance_setup_plan(&plan)?;
        }
        return Ok(0);
    }
    let result = install::install_agent_guidance(args, install_command)?;
    if output_format == OutputFormat::Json {
        output::print_json(&result)?;
    } else {
        output::print_agent_guidance_setup_result(&result)?;
    }
    Ok(0)
}

fn agent_guidance_setup_command(args: &cli::AgentGuidanceSetupArgs) -> Vec<String> {
    let mut command = vec![
        current_executable_argument(),
        "agent".to_string(),
        "setup".to_string(),
    ];
    if let Some(workspace_root) = &args.workspace_root {
        command.push("--workspace-root".to_string());
        command.push(workspace_root.display().to_string());
    }
    for target in &args.agents_md {
        command.push("--agents-md".to_string());
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

fn root_setup_command(
    setup_args: &cli::AgentGuidanceSetupArgs,
    runtime_args: &cli::RuntimeArgs,
    no_onboard: bool,
) -> Vec<String> {
    let mut command = vec![current_executable_argument(), "setup".to_string()];
    if let Some(workspace_root) = &runtime_args.workspace_root {
        command.push("--workspace-root".to_string());
        command.push(workspace_root.display().to_string());
    } else if let Some(workspace_root) = &setup_args.workspace_root {
        command.push("--workspace-root".to_string());
        command.push(workspace_root.display().to_string());
    }
    if let Some(backend) = runtime_args.backend_name {
        command.push("--backend".to_string());
        command.push(backend.canonical().to_string());
    }
    for target in &setup_args.agents_md {
        command.push("--agents-md".to_string());
        command.push(target.display().to_string());
    }
    if setup_args.force {
        command.push("--force".to_string());
    }
    if setup_args.no_auto_exclude_git {
        command.push("--no-auto-exclude-git".to_string());
    }
    if no_onboard {
        command.push("--no-open-ide".to_string());
    }
    command
}

fn run_agent_setup_auto(
    args: cli::AgentSetupAutoArgs,
    output_format: OutputFormat,
    workspace_root: Option<PathBuf>,
) -> Result<i32> {
    let cwd = workspace_root.unwrap_or_else(|| env::current_dir().unwrap_or_else(|_| ".".into()));
    let selection = agent_setup_auto_selection(&cwd, &args)?;
    if args.dry_run {
        let plan = agent_setup_auto_plan(&selection, &args, &cwd);
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

    let has_skill_root = [
        ".agents/skills",
        ".codex/skills",
        ".github/skills",
        ".claude/skills",
    ]
    .iter()
    .any(|candidate| cwd.join(candidate).is_dir());
    let has_instruction_root = [
        ".agents/instructions",
        ".codex/instructions",
        ".github/instructions",
        ".claude/instructions",
    ]
    .iter()
    .any(|candidate| cwd.join(candidate).is_dir());

    if has_skill_root {
        AgentSetupAutoSelection {
            harness: cli::AgentSetupHarness::Skill,
            source: install::AgentSetupSelectionSource::Repository,
            reason: "Repository detection found a skill root.".to_string(),
        }
    } else if has_instruction_root {
        AgentSetupAutoSelection {
            harness: cli::AgentSetupHarness::Instructions,
            source: install::AgentSetupSelectionSource::Repository,
            reason: "Repository detection found an instruction root.".to_string(),
        }
    } else {
        AgentSetupAutoSelection {
            harness: cli::AgentSetupHarness::Copilot,
            source: install::AgentSetupSelectionSource::Repository,
            reason: "Repository detection found `.github` or no skill/instruction roots."
                .to_string(),
        }
    }
}

fn agent_setup_auto_plan(
    selection: &AgentSetupAutoSelection,
    args: &cli::AgentSetupAutoArgs,
    cwd: &Path,
) -> install::AgentSetupAutoPlan {
    let target_dir = args
        .target_dir
        .clone()
        .or_else(|| Some(default_agent_up_target_dir(selection.harness, cwd)));
    let command = agent_setup_install_command(selection.harness, args, target_dir.as_ref());
    let mut plan = install::AgentSetupAutoPlan::new(
        selection.harness,
        selection.source,
        selection.reason.clone(),
        command,
        target_dir,
    );
    plan.dry_run = args.dry_run;
    plan
}

fn default_agent_up_target_dir(harness: cli::AgentSetupHarness, workspace_root: &Path) -> PathBuf {
    match harness {
        cli::AgentSetupHarness::Auto => unreachable!("auto harness must be resolved"),
        cli::AgentSetupHarness::Copilot => workspace_root.join(".github"),
        cli::AgentSetupHarness::Skill => first_existing_or_default(
            workspace_root,
            &[
                ".agents/skills",
                ".codex/skills",
                ".github/skills",
                ".claude/skills",
            ],
            ".agents/skills",
        ),
        cli::AgentSetupHarness::Instructions => first_existing_or_default(
            workspace_root,
            &[
                ".agents/instructions",
                ".codex/instructions",
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

fn agent_setup_install_command(
    harness: cli::AgentSetupHarness,
    args: &cli::AgentSetupAutoArgs,
    target_dir: Option<&PathBuf>,
) -> Vec<String> {
    let mut command = vec![
        current_executable_argument(),
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
    if let Some(target_dir) = target_dir {
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
            dry_run: false,
        }),
        cli::AgentSetupHarness::Skill => cli::InstallCommand::Skill(cli::ResourceInstallArgs {
            target_dir: args.target_dir,
            name: None,
            source_dir: None,
            force: args.force,
            no_auto_exclude_git: args.no_auto_exclude_git,
            dry_run: false,
        }),
        cli::AgentSetupHarness::Instructions => {
            cli::InstallCommand::Instructions(cli::ResourceInstallArgs {
                target_dir: args.target_dir,
                name: None,
                source_dir: None,
                force: args.force,
                no_auto_exclude_git: args.no_auto_exclude_git,
                dry_run: false,
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

fn run_developer(command: cli::DeveloperCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::DeveloperCommand::Runtime(args) => run_runtime(args.command, output_format),
        cli::DeveloperCommand::Inspect(args) => run_inspect(args.command, output_format),
        cli::DeveloperCommand::Machine(args) => run_machine(args.command, output_format),
        cli::DeveloperCommand::Release(args) => run_release(args.command, output_format),
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
    if let Some(plan) = install_dry_run_plan(&command) {
        if output_format == OutputFormat::Json {
            output::print_json(&plan)?;
        } else {
            output::print_agent_setup_auto_plan(&plan)?;
        }
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

fn install_dry_run_plan(command: &cli::InstallCommand) -> Option<install::AgentSetupAutoPlan> {
    match command {
        cli::InstallCommand::Copilot(args) if args.dry_run => Some(resource_install_plan(
            cli::AgentSetupHarness::Copilot,
            args.target_dir.clone(),
            None,
            None,
            args.force,
            args.no_auto_exclude_git,
        )),
        cli::InstallCommand::Skill(args) if args.dry_run => Some(resource_install_plan(
            cli::AgentSetupHarness::Skill,
            args.target_dir.clone(),
            args.name.clone(),
            args.source_dir.clone(),
            args.force,
            args.no_auto_exclude_git,
        )),
        cli::InstallCommand::Instructions(args) if args.dry_run => Some(resource_install_plan(
            cli::AgentSetupHarness::Instructions,
            args.target_dir.clone(),
            args.name.clone(),
            args.source_dir.clone(),
            args.force,
            args.no_auto_exclude_git,
        )),
        _ => None,
    }
}

fn resource_install_plan(
    harness: cli::AgentSetupHarness,
    target_dir: Option<PathBuf>,
    name: Option<String>,
    source_dir: Option<PathBuf>,
    force: bool,
    no_auto_exclude_git: bool,
) -> install::AgentSetupAutoPlan {
    let command = resource_install_command(
        harness,
        target_dir.as_ref(),
        name.as_deref(),
        source_dir.as_ref(),
        force,
        no_auto_exclude_git,
    );
    let mut plan = install::AgentSetupAutoPlan::new(
        harness,
        install::AgentSetupSelectionSource::Explicit,
        "Concrete agent resource setup command selected.".to_string(),
        command,
        target_dir,
    );
    plan.dry_run = true;
    plan
}

fn resource_install_command(
    harness: cli::AgentSetupHarness,
    target_dir: Option<&PathBuf>,
    name: Option<&str>,
    source_dir: Option<&PathBuf>,
    force: bool,
    no_auto_exclude_git: bool,
) -> Vec<String> {
    let mut command = vec![
        current_executable_argument(),
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
    if let Some(target_dir) = target_dir {
        command.push("--target-dir".to_string());
        command.push(target_dir.display().to_string());
    }
    if let Some(name) = name {
        command.push("--name".to_string());
        command.push(name.to_string());
    }
    if let Some(source_dir) = source_dir {
        command.push("--source-dir".to_string());
        command.push(source_dir.display().to_string());
    }
    if force {
        command.push("--force".to_string());
    }
    if no_auto_exclude_git {
        command.push("--no-auto-exclude-git".to_string());
    }
    command
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
