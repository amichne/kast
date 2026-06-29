pub fn run(args: AgentArgs) -> Result<i32> {
    let envelope = execute(args.command);
    let exit_code = if envelope.ok { 0 } else { 1 };
    output::print_json(&envelope)?;
    Ok(exit_code)
}

fn execute(command: AgentCommand) -> AgentEnvelope {
    if matches!(
        command,
        AgentCommand::Up(_)
            | AgentCommand::Ready(_)
            | AgentCommand::Setup(_)
            | AgentCommand::Lsp(_)
    ) {
        return error_envelope(
            "agent/operator".to_string(),
            None,
            agent_error(
                "AGENT_COMMAND_UNSUPPORTED",
                "`kast agent up`, `kast agent ready`, `kast agent setup`, and `kast agent lsp` are operator commands handled before JSON envelope dispatch.",
            ),
        );
    }
    if let AgentCommand::Workflow(args) = command {
        return execute_workflow(args.command);
    }
    if matches!(command, AgentCommand::Tools) {
        return execute_tools();
    }
    let request = match command {
        AgentCommand::Up(_)
        | AgentCommand::Ready(_)
        | AgentCommand::Setup(_)
        | AgentCommand::Lsp(_) => {
            unreachable!("operator agent commands are handled before request prep")
        }
        AgentCommand::Tools => unreachable!("agent tools is handled before request prep"),
        AgentCommand::Call(args) => prepare_call(args),
        AgentCommand::Workflow(_) => unreachable!("workflow is handled before request prep"),
        other => Ok(prepare_alias(other)),
    };
    let request = match request {
        Ok(request) => request,
        Err(error) => return error_envelope(error.method, error.request, error.error),
    };
    execute_request(request)
}
