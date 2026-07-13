#[derive(Debug, Clone)]
enum AgentResultView<Field> {
    Compact,
    Fields(Vec<Field>),
    Count,
    Verbose,
    Explain,
}

impl<Field: Clone> AgentResultView<Field> {
    fn from_parts(
        verbose: bool,
        explain: bool,
        fields: &[Field],
        count: bool,
    ) -> Self {
        if verbose {
            Self::Verbose
        } else if explain {
            Self::Explain
        } else if count {
            Self::Count
        } else if fields.is_empty() {
            Self::Compact
        } else {
            Self::Fields(fields.to_vec())
        }
    }

    fn detailed(&self) -> bool {
        matches!(self, Self::Verbose | Self::Explain)
    }
}

#[derive(Debug, Clone)]
enum AgentProjectionRequest {
    Passthrough,
    Symbol(AgentResultView<AgentSymbolField>),
    Diagnostics(AgentResultView<AgentDiagnosticsField>),
    Mutation(AgentResultView<AgentMutationField>),
    Verify(AgentResultView<AgentVerifyField>),
}

impl AgentProjectionRequest {
    fn for_command(command: &AgentCommand) -> Self {
        match command {
            AgentCommand::Verify(args) => Self::Verify(verify_result_view(&args.view)),
            AgentCommand::Symbol(args) => Self::Symbol(symbol_result_view(&args.view)),
            AgentCommand::Diagnostics(args) => {
                Self::Diagnostics(diagnostics_result_view(&args.view))
            }
            AgentCommand::Rename(args) => Self::Mutation(mutation_result_view(&args.mutation.view)),
            AgentCommand::AddFile(args) => {
                Self::Mutation(mutation_result_view(&args.mutation.view))
            }
            AgentCommand::AddDeclaration(args) | AgentCommand::AddImplementation(args) => {
                Self::Mutation(mutation_result_view(&args.mutation.view))
            }
            AgentCommand::AddStatement(args) => {
                Self::Mutation(mutation_result_view(&args.mutation.view))
            }
            AgentCommand::ReplaceDeclaration(args) => {
                Self::Mutation(mutation_result_view(&args.mutation.view))
            }
            AgentCommand::Operation(args) => match &args.command {
                AgentOperationCommand::Status(args) | AgentOperationCommand::Cancel(args) => {
                    Self::Mutation(mutation_result_view(&args.view))
                }
            },
            AgentCommand::Lsp(_)
            | AgentCommand::Impact(_)
            | AgentCommand::Tools(_)
            | AgentCommand::Call(_)
            | AgentCommand::Workflow(_) => Self::Passthrough,
        }
    }

    fn project(self, envelope: AgentEnvelope) -> AgentEnvelope {
        match self {
            Self::Symbol(view) => project_symbol_envelope(envelope, view),
            Self::Diagnostics(view) => project_diagnostics_envelope(envelope, view),
            Self::Mutation(view) => project_mutation_envelope(envelope, view),
            Self::Verify(view) => project_verify_envelope(envelope, view),
            Self::Passthrough => envelope,
        }
    }
}

fn verify_result_view(view: &AgentVerifyViewArgs) -> AgentResultView<AgentVerifyField> {
    AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count)
}

fn symbol_result_view(view: &AgentSymbolViewArgs) -> AgentResultView<AgentSymbolField> {
    AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count)
}

fn diagnostics_result_view(
    view: &AgentDiagnosticsViewArgs,
) -> AgentResultView<AgentDiagnosticsField> {
    AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count)
}

fn mutation_result_view(view: &AgentMutationViewArgs) -> AgentResultView<AgentMutationField> {
    AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentStepCommandProjectionInput {
    #[serde(rename = "type")]
    result_type: String,
    steps: Vec<AgentStepProjectionInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentStepProjectionInput {
    name: String,
    method: String,
    ok: bool,
    #[serde(default)]
    result: Option<Value>,
    #[serde(default)]
    error: Option<AgentError>,
}

impl AgentStepCommandProjectionInput {
    fn validated(result: Value) -> std::result::Result<Self, String> {
        let input = serde_json::from_value::<Self>(result).map_err(|error| error.to_string())?;
        if input.result_type != "KAST_AGENT_COMMAND" {
            return Err(format!(
                "expected KAST_AGENT_COMMAND, found {}",
                input.result_type
            ));
        }
        Ok(input)
    }

    fn step(&self, method: &str) -> Option<&AgentStepProjectionInput> {
        self.steps.iter().find(|step| step.method == method)
    }

    fn first_error(&self) -> Option<AgentError> {
        self.steps.iter().find_map(|step| step.error.clone())
    }
}
