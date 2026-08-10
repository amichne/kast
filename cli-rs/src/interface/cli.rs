use clap::{Args, CommandFactory, Parser, Subcommand, ValueEnum};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

include!("cli/root.rs");
include!("cli/workspace/config.rs");
include!("cli/inspect_metrics_demo_rpc.rs");
include!("cli/agent.rs");
include!("cli/agent/agent_surface.rs");
include!("cli/release_package_generate.rs");
include!("cli/workspace/runtime.rs");
include!("cli/codex.rs");
include!("cli/command_groups.rs");
include!("cli/support/conversions.rs");
include!("cli/support/helpers.rs");
include!("cli/support/shared.rs");
