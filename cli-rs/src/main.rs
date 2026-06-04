mod backend;
mod cli;
mod config;
mod daemon;
mod demo;
mod devin_runtime;
mod error;
mod install;
mod metrics;
mod metrics_database;
mod rpc;
mod runtime;
mod self_mgmt;
mod source_index_db;
mod source_index_schema;
mod symbol_query;

use clap::{CommandFactory, Parser};
use cli::{Cli, Command};
use error::{CliError, Result};
use std::io::{self, Write};

const SCHEMA_VERSION: u32 = 3;

fn main() {
    let exit_code = match run() {
        Ok(code) => code,
        Err(error) => {
            let response = error.to_response();
            let _ = serde_json::to_writer_pretty(io::stderr(), &response);
            let _ = writeln!(io::stderr());
            1
        }
    };
    std::process::exit(exit_code);
}

fn run() -> Result<i32> {
    let cli = match Cli::try_parse() {
        Ok(cli) => cli,
        Err(error) if !error.use_stderr() => {
            error.print()?;
            return Ok(0);
        }
        Err(error) => return Err(CliError::from_clap(error)),
    };
    match cli.command.unwrap_or(Command::Help { topic: vec![] }) {
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
        Command::Config { command } => match command {
            cli::ConfigCommand::Init => {
                let path = config::init_config()?;
                println!("Wrote {}", path.display());
                Ok(0)
            }
        },
        Command::Daemon { command } => match command {
            cli::DaemonCommand::Start(args) => daemon::run_foreground(args),
        },
        Command::Backend { command } => {
            let result = backend::run(command)?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(0)
        }
        Command::Rpc(args) => {
            let response = runtime::rpc_passthrough(args)?;
            println!("{response}");
            Ok(0)
        }
        Command::Up(args) => {
            let result = runtime::workspace_ensure(args)?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(0)
        }
        Command::Status(args) => {
            let result = runtime::workspace_status(args)?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(0)
        }
        Command::Stop(args) => {
            let result = runtime::workspace_stop(args)?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(0)
        }
        Command::Capabilities(args) => {
            let result = runtime::capabilities(args)?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(0)
        }
        Command::Demo(args) => demo::run(args),
        Command::Metrics { command } => metrics::run(command),
        Command::Install(args) => {
            let result = install::install(args)?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(0)
        }
        Command::DevinRuntime { command } => {
            let result = devin_runtime::run(command)?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(0)
        }
        Command::Info => {
            let result = self_mgmt::status()?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(0)
        }
        Command::Doctor => {
            let result = self_mgmt::doctor()?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(if result.ok { 0 } else { 1 })
        }
        Command::Uninstall(args) => {
            let result = install::uninstall(args)?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(0)
        }
        Command::VerifyExtension => {
            let result = install::verify_extension()?;
            serde_json::to_writer_pretty(io::stdout(), &result)?;
            println!();
            Ok(if result.ok { 0 } else { 1 })
        }
    }
}
