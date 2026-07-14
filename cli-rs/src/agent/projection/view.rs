#[derive(Debug, Clone)]
enum AgentResultView<Field> {
    Compact,
    Fields(Vec<Field>),
    Count,
    Verbose,
    Explain,
}

impl<Field: Clone> AgentResultView<Field> {
    fn from_parts(verbose: bool, explain: bool, fields: &[Field], count: bool) -> Self {
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
    Symbol {
        view: AgentResultView<AgentSymbolField>,
        relation_limit: usize,
    },
    Diagnostics {
        view: AgentResultView<AgentDiagnosticsField>,
        result_limit: usize,
    },
    Impact(AgentResultView<AgentImpactField>),
    Mutation(AgentResultView<AgentMutationField>),
    Verify(AgentResultView<AgentVerifyField>),
    WorkspaceFiles(AgentResultView<AgentWorkspaceFilesField>),
}

impl AgentProjectionRequest {
    fn for_command(command: &AgentCommand) -> Self {
        match command {
            AgentCommand::Verify(args) => Self::Verify(verify_result_view(&args.view)),
            AgentCommand::WorkspaceFiles(args) => {
                Self::WorkspaceFiles(workspace_files_result_view(&args.view))
            }
            AgentCommand::Symbol(args) => Self::Symbol {
                view: symbol_result_view(&args.view),
                relation_limit: AgentRelationResultBudget::try_from(args.limit)
                    .map(AgentRelationResultBudget::projection_limit)
                    .unwrap_or_default(),
            },
            AgentCommand::Diagnostics(args) => Self::Diagnostics {
                view: diagnostics_result_view(&args.view),
                result_limit: AgentDiagnosticsResultBudget::try_from(args.limit)
                    .map(AgentDiagnosticsResultBudget::projection_limit)
                    .unwrap_or_default(),
            },
            AgentCommand::Impact(args) => Self::Impact(impact_result_view(&args.view)),
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
            | AgentCommand::Tools(_)
            | AgentCommand::Call(_)
            | AgentCommand::Workflow(_) => Self::Passthrough,
        }
    }

    fn project(self, envelope: AgentEnvelope) -> AgentEnvelope {
        match self {
            Self::Symbol {
                view,
                relation_limit,
            } => project_symbol_envelope(envelope, view, relation_limit),
            Self::Diagnostics { view, result_limit } => {
                project_diagnostics_envelope(envelope, view, result_limit)
            }
            Self::Impact(view) => project_impact_envelope(envelope, view),
            Self::Mutation(view) => project_mutation_envelope(envelope, view),
            Self::Verify(view) => project_verify_envelope(envelope, view),
            Self::WorkspaceFiles(_view) => envelope,
            Self::Passthrough => envelope,
        }
    }
}

fn verify_result_view(view: &AgentVerifyViewArgs) -> AgentResultView<AgentVerifyField> {
    AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count)
}

fn workspace_files_result_view(
    view: &AgentWorkspaceFilesViewArgs,
) -> AgentResultView<AgentWorkspaceFilesField> {
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

fn impact_result_view(view: &AgentImpactViewArgs) -> AgentResultView<AgentImpactField> {
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
    #[serde(default)]
    file_paths: Vec<String>,
    #[serde(default)]
    semantic_workspace: Option<AgentSemanticWorkspaceProjection>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSemanticWorkspaceProjection {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    backend_name: Option<String>,
    workspace_root: String,
    workspace_kind: AgentSemanticWorkspaceKindProjection,
    source_module_names: Vec<String>,
    limitations: Vec<AgentSemanticWorkspaceLimitationProjection>,
    evidence_quality: AgentSemanticEvidenceQualityProjection,
    next_actions: Vec<AgentSemanticWorkspaceNextActionProjection>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    backend_candidates: Vec<AgentSemanticBackendCandidateProjection>,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentSemanticWorkspaceKindProjection {
    PrimaryCheckout,
    LinkedWorktree,
    DisposableCheckout,
    StandaloneGradleWorkspace,
    UnsupportedProject,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentSemanticWorkspaceLimitationProjection {
    WorkspaceUnprepared,
    SourceModulesUnavailable,
    UnsupportedProject,
    MutationAuthorityRequired,
    BackendSelectionAmbiguous,
    RuntimeIndexing,
    ReferenceIndexUnavailable,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentSemanticEvidenceQualityProjection {
    Unavailable,
    CompilerBacked,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSemanticWorkspaceNextActionProjection {
    kind: AgentSemanticWorkspaceNextActionKindProjection,
    command: String,
    mutates_global_install_authority: bool,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentSemanticWorkspaceNextActionKindProjection {
    PrepareIdeaWorkspace,
    UseHeadlessDistribution,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSemanticBackendCandidateProjection {
    backend_name: String,
    backend_version: String,
    workspace_root: String,
    ready: bool,
    evidence_quality: AgentSemanticEvidenceQualityProjection,
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
