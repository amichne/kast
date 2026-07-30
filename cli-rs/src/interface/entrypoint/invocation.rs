fn invoked_entrypoint() -> Option<Entrypoint> {
    match Path::new(&current_executable_argument())
        .file_stem()
        .and_then(|name| name.to_str())
    {
        Some("kast") => Some(Entrypoint::Agent),
        Some("kastctl") => Some(Entrypoint::Control),
        _ => None,
    }
}

fn unrecognized_entrypoint_main() -> i32 {
    let invoked = Path::new(&current_executable_argument())
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("unknown")
        .to_string();
    let error = CliError::new(
        "CLI_USAGE",
        format!("This executable name is not supported: `{invoked}`. Run `kast --help`."),
    );
    let _ = print_agent_error(&error);
    2
}

#[derive(Debug, Serialize)]
struct AgentError<'a> {
    error: &'a str,
    message: &'a str,
    next: &'static str,
}

fn print_agent_error(error: &CliError) -> Result<()> {
    output::print_structured(
        &AgentError {
            error: error.code,
            message: &error.message,
            next: "Run `kast --help` for valid commands and arguments.",
        },
        OutputFormat::Toon,
    )
}
