use clap::{Args, CommandFactory, Parser, Subcommand, ValueEnum};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

include!("cli/root.rs");
include!("cli/inspect_metrics_demo_rpc.rs");
include!("cli/agent.rs");
include!("cli/release_package_generate.rs");
include!("cli/runtime_lsp.rs");
include!("cli/codex.rs");
include!("cli/command_groups.rs");
include!("cli/conversions.rs");
include!("cli/install_machine.rs");
include!("cli/shared.rs");
include!("cli/helpers.rs");
