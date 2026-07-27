#[cfg(test)]
mod tests {
    use super::*;

    fn interactive_human_environment() -> OutputEnvironment {
        OutputEnvironment {
            stdin_terminal: true,
            stdout_terminal: true,
            ci: false,
            dumb_terminal: false,
            agent_process: false,
        }
    }

    #[test]
    fn output_environment_allows_human_only_for_interactive_non_agent_terminal() {
        assert!(interactive_human_environment().allows_human_output());

        for environment in [
            OutputEnvironment {
                stdin_terminal: false,
                ..interactive_human_environment()
            },
            OutputEnvironment {
                stdout_terminal: false,
                ..interactive_human_environment()
            },
            OutputEnvironment {
                ci: true,
                ..interactive_human_environment()
            },
            OutputEnvironment {
                dumb_terminal: true,
                ..interactive_human_environment()
            },
            OutputEnvironment {
                agent_process: true,
                ..interactive_human_environment()
            },
        ] {
            assert!(!environment.allows_human_output(), "{environment:?}");
        }
    }

    #[test]
    fn agent_commands_default_to_toon_even_in_an_interactive_terminal() {
        let cli = Cli::try_parse_from(["kast", "agent"]).expect("parse agent home");

        assert_eq!(
            effective_output_format(None, cli.command.as_ref()),
            OutputFormat::Toon
        );
        assert_eq!(
            effective_output_format(Some(OutputFormat::Json), cli.command.as_ref()),
            OutputFormat::Json
        );
    }
}
