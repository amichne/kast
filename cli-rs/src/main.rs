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
        Command::Package(args) => {
            let result = package::run(args)?;
            if output_format == OutputFormat::Json {
                output::print_json(&result)?;
            } else {
                output::print_package_result(&result)?;
            }
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
        Command::Paths(args) => {
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
        Command::Install(cli::InstallArgs {
            command: cli::InstallCommand::Completion(completion_args),
        }) => {
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
        Command::Doctor(args) => {
            let result = self_mgmt::doctor(args.repair)?;
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
