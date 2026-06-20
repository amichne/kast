mod backend;
mod catalog_schema;
mod cli;
mod config;
mod contract_gen;
mod daemon;
mod demo;
mod error;
mod install;
mod interaction;
mod lsp;
mod metrics;
mod metrics_database;
mod output;
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
use std::io;
use std::path::Path;

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
    maybe_repair_after_cli_upgrade(&command)?;
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
        Command::Validate(args) => {
            let result = validate::run(args)?;
            output::print_json(&result)?;
            Ok(if result.ok { 0 } else { 1 })
        }
        Command::Generate(args) => match args.command {
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
        },
        Command::Up(args) => {
            let result = runtime::workspace_ensure(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_workspace_ensure(&result)?;
            }
            Ok(0)
        }
        Command::Status(args) => {
            let result = runtime::workspace_status(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_workspace_status(&result)?;
            }
            Ok(0)
        }
        Command::Stop(args) => {
            let result = runtime::workspace_stop(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_stop_result(&result)?;
            }
            Ok(0)
        }
        Command::Restart(args) => {
            let result = runtime::workspace_restart(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_restart_result(&result)?;
            }
            Ok(0)
        }
        Command::Capabilities(args) => {
            let result = runtime::capabilities(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_capabilities(&result)?;
            }
            Ok(0)
        }
        Command::Lsp(args) => lsp::run(args),
        Command::Demo(args) => demo::run(args),
        Command::Metrics { command } => metrics::run(command, output_format),
        Command::Setup(args) => {
            let mut reporter = install_reporter(output_format);
            let result = install::setup(args, reporter.as_mut())?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_setup(&result)?;
            }
            Ok(0)
        }
        Command::Install(args)
            if should_prompt_for_affected_install_apply(&args, output_format) =>
        {
            run_interactive_affected_install(args)
        }
        Command::Install(args)
            if matches!(args.command, Some(cli::InstallCommand::Completion(_))) =>
        {
            let Some(cli::InstallCommand::Completion(completion_args)) = args.command else {
                unreachable!("install completion guard should only match completion commands")
            };
            print_completion(completion_args);
            Ok(0)
        }
        Command::Install(args) => {
            let mut reporter = install_reporter(output_format);
            let result = install::install(args, reporter.as_mut())?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_install_result(&result)?;
            }
            Ok(0)
        }
        Command::Doctor => {
            let result = self_mgmt::doctor()?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_doctor(&result)?;
            }
            Ok(if result.ok { 0 } else { 1 })
        }
    }
}

fn install_reporter(output_format: OutputFormat) -> Box<dyn install::InstallReporter> {
    if output_format == OutputFormat::Human {
        Box::new(install::HumanInstallReporter::new())
    } else {
        Box::new(install::NoopInstallReporter)
    }
}

fn should_prompt_for_affected_install_apply(
    args: &cli::InstallArgs,
    output_format: OutputFormat,
) -> bool {
    interaction::PromptPolicy::current(output_format).is_enabled()
        && matches!(
            &args.command,
            Some(cli::InstallCommand::Affected(cli::AffectedInstallArgs {
                apply: false,
                ..
            }))
        )
}

fn run_interactive_affected_install(args: cli::InstallArgs) -> Result<i32> {
    let mut reporter = install_reporter(OutputFormat::Human);
    let planned = install::install(args.clone(), reporter.as_mut())?;
    let action_count = match &planned {
        install::InstallResult::Affected(result) => result.actions.len(),
        _ => 0,
    };
    output::print_install_result(&planned)?;
    if action_count == 0 {
        return Ok(0);
    }

    if interaction::confirm_affected_install_apply(action_count)?
        == interaction::Confirmation::Accepted
    {
        let mut apply_args = args;
        let Some(cli::InstallCommand::Affected(affected_args)) = &mut apply_args.command else {
            unreachable!("interactive affected install only handles affected install arguments")
        };
        affected_args.apply = true;
        let applied = install::install(apply_args, reporter.as_mut())?;
        output::print_install_result(&applied)?;
    }
    Ok(0)
}

fn maybe_repair_after_cli_upgrade(command: &Command) -> Result<()> {
    if matches!(
        command,
        Command::Help { .. }
            | Command::Version
            | Command::Validate(_)
            | Command::Generate(_)
            | Command::Lsp(_)
            | Command::Setup(_)
            | Command::Install(cli::InstallArgs {
                command: Some(
                    cli::InstallCommand::Affected(_) | cli::InstallCommand::Completion(_)
                ),
                ..
            })
    ) {
        return Ok(());
    }
    match install::repair_if_running_cli_version_changed() {
        Ok(_) => Ok(()),
        Err(error) if matches!(command, Command::Doctor) && error.code == "CONFIG_ERROR" => Ok(()),
        Err(error) => Err(error),
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
