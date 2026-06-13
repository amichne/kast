mod backend;
mod cli;
mod config;
mod daemon;
mod demo;
mod error;
mod install;
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

use clap::{CommandFactory, Parser};
use cli::{Cli, Command, OutputFormat, ShellKind};
use error::{CliError, Result};
use std::io;

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
            let result = install::setup(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_setup(&result)?;
            }
            Ok(0)
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
            let result = install::install(args)?;
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

fn maybe_repair_after_cli_upgrade(command: &Command) -> Result<()> {
    if matches!(
        command,
        Command::Help { .. }
            | Command::Version
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
