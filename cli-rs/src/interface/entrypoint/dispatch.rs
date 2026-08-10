fn run(cli: Cli, output_format: OutputFormat) -> Result<i32> {
    let command = cli
        .command
        .unwrap_or_else(|| Command::Context(default_runtime_args()));
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
        Command::Config(args) => run_config(args.command, output_format),
        Command::Setup(args) => run_setup(args, output_format),
        Command::Ready(args) => run_ready(args, output_format),
        Command::Demo(args) => demo::run_public(args, output_format),
        Command::Rpc(args) => run_rpc(args, output_format),
        Command::Developer(args) => run_developer(args.command, output_format),
        Command::Doctor(args) => run_ready(args.into(), output_format),
        Command::Agent(args) => run_agent(args, output_format),
        Command::RuntimeServiceEntrypoint(args) => {
            runtime::service_entrypoint(args)?;
            Ok(0)
        }
    }
}

fn run_config(command: cli::ConfigCommand, output_format: OutputFormat) -> Result<i32> {
    let output_format = if output_format.is_structured() {
        output_format
    } else {
        OutputFormat::Toon
    };
    match command {
        cli::ConfigCommand::List(args) => output::print_structured(
            &config::list_workspace_config(args.workspace_root)?,
            output_format,
        )?,
        cli::ConfigCommand::Set(args) => output::print_structured(
            &config::set_workspace_config(args.workspace_root, args.key, args.value)?,
            output_format,
        )?,
        cli::ConfigCommand::Add(args) => output::print_structured(
            &config::add_workspace_config(args.workspace_root, args.key, args.pattern)?,
            output_format,
        )?,
        cli::ConfigCommand::Remove(args) => output::print_structured(
            &config::remove_workspace_config(args.workspace_root, args.key, args.pattern)?,
            output_format,
        )?,
        cli::ConfigCommand::Unset(args) => output::print_structured(
            &config::unset_workspace_config(args.workspace_root, args.key)?,
            output_format,
        )?,
    }
    Ok(0)
}

fn run_rpc(args: cli::RpcArgs, output_format: OutputFormat) -> Result<i32> {
    let response = runtime::raw_request_passthrough(args.request, args.workspace_root)?;
    if output_format == OutputFormat::Json {
        println!("{response}");
        return Ok(0);
    }
    let response: serde_json::Value = serde_json::from_str(&response)?;
    if output_format == OutputFormat::Human
        && let Some(markdown) = repository_intelligence::render_markdown_report(&response)
    {
        output::print_markdown(&markdown)?;
    } else {
        output::print_structured(&response, output_format)?;
    }
    Ok(0)
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
        description: "Compiler-backed Kotlin semantic navigation, editing, diagnostics, and transactional release setup.",
        workspace_root: workspace_root.display().to_string(),
        output_default: "Kast agent commands always default to TOON; JSON remains deprecated compatibility output.",
        commands: context_command_hints(),
        help: vec![
            "Run `kastctl --help` for command reference.".to_string(),
            "Rerun `kastctl setup --source <bundle>` whenever installation readiness fails."
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
    vec![
        ContextCommandHint {
            command: "kastctl developer inspect lifecycle --workspace-root <repo>".to_string(),
            purpose: "Inspect lifecycle evidence without starting or stopping a runtime.".to_string(),
        },
        ContextCommandHint {
            command: "kastctl config list --workspace-root <repo>".to_string(),
            purpose: "Inspect effective workspace configuration and mutable fields.".to_string(),
        },
        ContextCommandHint {
            command: "kastctl agent verify --workspace-root <repo>".to_string(),
            purpose: "Check backend health, runtime state, and capabilities.".to_string(),
        },
        ContextCommandHint {
            command: "kastctl agent symbol --query <name> --workspace-root <repo>".to_string(),
            purpose: "Resolve Kotlin symbol identity before reading or editing.".to_string(),
        },
    ]
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
    let result = install::setup(args)?;
    output::print_structured(
        &result,
        if output_format.is_structured() {
            output_format
        } else {
            OutputFormat::Toon
        },
    )?;
    Ok(0)
}

fn run_ready(args: cli::ReadyArgs, output_format: OutputFormat) -> Result<i32> {
    let cli::ReadyArgs { runtime, target } = args;
    let workspace_root = runtime
        .workspace_root
        .as_deref()
        .map(|path| config::resolve_workspace_root(Some(path.to_path_buf())))
        .transpose()?;
    let result = self_mgmt::doctor(target, workspace_root.as_deref())?;
    if output_format.is_structured() {
        output::print_structured(&result, output_format)?;
    } else {
        output::print_ready(&result)?;
    }
    Ok(if result.ok { 0 } else { 1 })
}

fn run_agent(args: cli::AgentArgs, output_format: OutputFormat) -> Result<i32> {
    match args.command {
        None => Err(CliError::new(
            "CLI_USAGE",
            "An agent command is required; run `kast agent --help`.",
        )),
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

fn run_developer(command: cli::DeveloperCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::DeveloperCommand::Inspect(args) => run_inspect(args.command, output_format),
        cli::DeveloperCommand::Release(args) => run_release(args.command, output_format),
        cli::DeveloperCommand::AgentHook(args) => {
            codex::run(cli::CodexCommand::Hook(args), output_format)
        }
        cli::DeveloperCommand::Codex(args) => codex::run(args.command, output_format),
    }
}

fn run_inspect(command: cli::InspectCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::InspectCommand::Lifecycle(args) => {
            output::print_structured(
                &runtime::inspect_lifecycle(args.workspace_root),
                if output_format.is_structured() { output_format } else { OutputFormat::Toon },
            )?;
            Ok(0)
        }
        cli::InspectCommand::Paths(args) => run_paths(args, output_format),
        cli::InspectCommand::Metrics { command } => metrics::run(command, output_format),
        cli::InspectCommand::Catalog(args) => run_validate(args),
    }
}

fn run_release(command: cli::ReleaseCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        cli::ReleaseCommand::Package(args) => run_package(args, output_format),
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
