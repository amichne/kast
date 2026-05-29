use clap::Parser;
use serde::Serialize;
use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Instant;

#[derive(Debug, Parser)]
#[command(
    name = "kast-metrics-bench",
    about = "Run local source-index read benchmarks for cli-rs metrics paths."
)]
struct Args {
    /// Workspace root containing the source-index cache.
    #[arg(long)]
    workspace_root: PathBuf,
    /// Read a specific source-index.db instead of the workspace default.
    #[arg(long)]
    database: Option<PathBuf>,
    /// Fully-qualified symbol used for graph and impact lookups.
    #[arg(long)]
    symbol: String,
    /// Symbol query used for metrics search.
    #[arg(long)]
    query: String,
    /// Reverse-reference depth used for graph and impact.
    #[arg(long, default_value_t = 3)]
    depth: usize,
    /// Repetitions per operation and implementation.
    #[arg(long, default_value_t = 5)]
    iterations: usize,
    /// Rust kast binary to benchmark. Defaults to a sibling `kast` binary.
    #[arg(long)]
    rust_bin: Option<PathBuf>,
    /// Optional JVM/Kotlin kast binary to compare against.
    #[arg(long)]
    kotlin_bin: Option<PathBuf>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Report {
    workspace_root: String,
    database: Option<String>,
    symbol: String,
    query: String,
    depth: usize,
    iterations: usize,
    runs: Vec<OperationReport>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct OperationReport {
    implementation: &'static str,
    operation: &'static str,
    command: Vec<String>,
    status: RunStatus,
    timings_ms: Vec<f64>,
    min_ms: Option<f64>,
    median_ms: Option<f64>,
    max_ms: Option<f64>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
#[serde(tag = "kind")]
enum RunStatus {
    Ok,
    Skipped {
        reason: String,
    },
    Failed {
        exit_code: Option<i32>,
        stderr: String,
    },
}

#[derive(Debug, Clone, Copy)]
enum Operation {
    FanIn,
    Graph,
    Search,
    Impact,
}

impl Operation {
    fn name(self) -> &'static str {
        match self {
            Self::FanIn => "fan-in",
            Self::Graph => "graph",
            Self::Search => "search",
            Self::Impact => "impact",
        }
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    let rust_bin = args.rust_bin.clone().unwrap_or_else(default_rust_bin);
    let mut runs = Vec::new();

    for operation in [
        Operation::FanIn,
        Operation::Graph,
        Operation::Search,
        Operation::Impact,
    ] {
        runs.push(run_operation("rust", Some(&rust_bin), operation, &args));
    }

    let kotlin_bin = args.kotlin_bin.clone().or_else(default_kotlin_bin);
    for operation in [
        Operation::FanIn,
        Operation::Graph,
        Operation::Search,
        Operation::Impact,
    ] {
        runs.push(run_operation(
            "kotlin",
            kotlin_bin.as_deref(),
            operation,
            &args,
        ));
    }

    let report = Report {
        workspace_root: args.workspace_root.display().to_string(),
        database: args
            .database
            .as_ref()
            .map(|path| path.display().to_string()),
        symbol: args.symbol,
        query: args.query,
        depth: args.depth,
        iterations: args.iterations,
        runs,
    };
    serde_json::to_writer_pretty(std::io::stdout(), &report)?;
    println!();
    Ok(())
}

fn run_operation(
    implementation: &'static str,
    binary: Option<&Path>,
    operation: Operation,
    args: &Args,
) -> OperationReport {
    let Some(binary) = binary else {
        return OperationReport {
            implementation,
            operation: operation.name(),
            command: Vec::new(),
            status: RunStatus::Skipped {
                reason: "no compatible binary was found or provided".to_string(),
            },
            timings_ms: Vec::new(),
            min_ms: None,
            median_ms: None,
            max_ms: None,
        };
    };
    if !binary.is_file() {
        return OperationReport {
            implementation,
            operation: operation.name(),
            command: vec![binary.display().to_string()],
            status: RunStatus::Skipped {
                reason: format!("binary does not exist: {}", binary.display()),
            },
            timings_ms: Vec::new(),
            min_ms: None,
            median_ms: None,
            max_ms: None,
        };
    }

    let command_args = operation_args(operation, args);
    let mut timings = Vec::new();
    for _ in 0..args.iterations {
        let started = Instant::now();
        let output = Command::new(binary).args(&command_args).output();
        let elapsed_ms = started.elapsed().as_secs_f64() * 1000.0;
        let output = match output {
            Ok(output) => output,
            Err(error) => {
                return failed_report(
                    implementation,
                    operation,
                    binary,
                    &command_args,
                    None,
                    error.to_string(),
                    timings,
                );
            }
        };
        if !output.status.success() {
            return failed_report(
                implementation,
                operation,
                binary,
                &command_args,
                output.status.code(),
                String::from_utf8_lossy(&output.stderr).trim().to_string(),
                timings,
            );
        }
        timings.push(elapsed_ms);
    }

    let mut sorted = timings.clone();
    sorted.sort_by(f64::total_cmp);
    let median = sorted.get(sorted.len().saturating_sub(1) / 2).copied();
    OperationReport {
        implementation,
        operation: operation.name(),
        command: command(binary, &command_args),
        status: RunStatus::Ok,
        min_ms: sorted.first().copied(),
        median_ms: median,
        max_ms: sorted.last().copied(),
        timings_ms: timings,
    }
}

fn failed_report(
    implementation: &'static str,
    operation: Operation,
    binary: &Path,
    command_args: &[String],
    exit_code: Option<i32>,
    stderr: String,
    timings_ms: Vec<f64>,
) -> OperationReport {
    OperationReport {
        implementation,
        operation: operation.name(),
        command: command(binary, command_args),
        status: RunStatus::Failed { exit_code, stderr },
        min_ms: None,
        median_ms: None,
        max_ms: None,
        timings_ms,
    }
}

fn operation_args(operation: Operation, args: &Args) -> Vec<String> {
    let mut values = match operation {
        Operation::FanIn => vec!["metrics".to_string(), "fan-in".to_string()],
        Operation::Graph => vec!["metrics".to_string(), "graph".to_string()],
        Operation::Search => vec!["metrics".to_string(), "search".to_string()],
        Operation::Impact => vec!["metrics".to_string(), "impact".to_string()],
    };
    values.push("--workspace-root".to_string());
    values.push(args.workspace_root.display().to_string());
    if let Some(database) = &args.database {
        values.push("--database".to_string());
        values.push(database.display().to_string());
    }
    match operation {
        Operation::FanIn => {
            values.push("--limit".to_string());
            values.push("50".to_string());
        }
        Operation::Graph => {
            values.push("--depth".to_string());
            values.push(args.depth.to_string());
            values.push("--json".to_string());
            values.push(args.symbol.clone());
        }
        Operation::Search => {
            values.push("--limit".to_string());
            values.push("25".to_string());
            values.push(args.query.clone());
        }
        Operation::Impact => {
            values.push("--depth".to_string());
            values.push(args.depth.to_string());
            values.push(args.symbol.clone());
        }
    }
    values
}

fn command(binary: &Path, args: &[String]) -> Vec<String> {
    std::iter::once(binary.display().to_string())
        .chain(args.iter().cloned())
        .collect()
}

fn default_rust_bin() -> PathBuf {
    env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(|parent| parent.join(binary_name("kast"))))
        .unwrap_or_else(|| PathBuf::from(binary_name("kast")))
}

fn default_kotlin_bin() -> Option<PathBuf> {
    let candidate =
        PathBuf::from("../kast/kast-cli/build/install/kast/bin").join(binary_name("kast"));
    candidate.is_file().then_some(candidate)
}

fn binary_name(name: &str) -> String {
    if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.to_string()
    }
}
