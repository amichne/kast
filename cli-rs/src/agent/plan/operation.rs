impl From<KastChangeCommand> for StoredOperation {
    fn from(command: KastChangeCommand) -> Self {
        match command {
            KastChangeCommand::Rename { symbol, new_name } => Self::Rename { symbol, new_name },
            KastChangeCommand::AddFile { path } => Self::AddFile { path },
            KastChangeCommand::AddDeclaration { path } => Self::AddDeclaration { path },
            KastChangeCommand::AddImplementation { scope } => Self::AddImplementation { scope },
            KastChangeCommand::AddStatement { scope } => Self::AddStatement { scope },
            KastChangeCommand::Replace { symbol } => Self::Replace { symbol },
        }
    }
}

impl StoredOperation {
    fn name(&self) -> &'static str {
        match self {
            Self::Rename { .. } => "rename",
            Self::AddFile { .. } => "add-file",
            Self::AddDeclaration { .. } => "add-declaration",
            Self::AddImplementation { .. } => "add-implementation",
            Self::AddStatement { .. } => "add-statement",
            Self::Replace { .. } => "replace",
        }
    }

    fn requires_content(&self) -> bool {
        !matches!(self, Self::Rename { .. })
    }

    fn normalize_from_preview(&mut self, preview: &Value) -> Result<()> {
        let normalized = preview
            .get("plan")
            .and_then(|plan| plan.get("filePath"))
            .and_then(Value::as_str);
        match self {
            Self::AddFile { path } | Self::AddDeclaration { path } => {
                *path = PathBuf::from(normalized.ok_or_else(|| {
                    CliError::new(
                        "KAST_INVALID_AGENT_RESULT",
                        "The validated change plan returned no normalized file path.",
                    )
                })?);
            }
            _ => {}
        }
        Ok(())
    }

    fn command(
        &self,
        workspace_root: PathBuf,
        content_file: Option<&Path>,
        apply: bool,
        idempotency_key: Option<String>,
        lease_id: Option<AgentWorkspaceLeaseId>,
    ) -> Result<AgentCommand> {
        let mut runtime = agent_adapter::agent_runtime(workspace_root);
        runtime.lease_id = lease_id;
        let mutation = AgentMutationApplyArgs {
            apply,
            idempotency_key,
            view: Default::default(),
        };
        let content_file = || {
            content_file.map(Path::to_path_buf).ok_or_else(|| {
                CliError::new(
                    "KAST_PLAN_CONTENT_UNAVAILABLE",
                    "This change operation requires stored Kotlin content.",
                )
            })
        };
        Ok(match self {
            Self::Rename { symbol, new_name } => AgentCommand::Rename(AgentRenameArgs {
                runtime,
                symbol: Some(symbol.clone()),
                selector_handle: None,
                new_name: new_name.clone(),
                kind: None,
                file_hint: None,
                containing_type: None,
                mutation,
            }),
            Self::AddFile { path } => AgentCommand::AddFile(AgentAddFileArgs {
                runtime,
                file_path: path.display().to_string(),
                content_file: content_file()?,
                mutation,
            }),
            Self::AddDeclaration { path } => {
                AgentCommand::AddDeclaration(AgentScopedMutationArgs {
                    runtime,
                    inside_scope: None,
                    inside_file: Some(path.display().to_string()),
                    at: Some(AgentPlacementAnchor::FileBottom),
                    after_symbol: None,
                    before_symbol: None,
                    content_file: content_file()?,
                    mutation,
                })
            }
            Self::AddImplementation { scope } => {
                AgentCommand::AddImplementation(AgentScopedMutationArgs {
                    runtime,
                    inside_scope: Some(scope.clone()),
                    inside_file: None,
                    at: Some(AgentPlacementAnchor::BodyEnd),
                    after_symbol: None,
                    before_symbol: None,
                    content_file: content_file()?,
                    mutation,
                })
            }
            Self::AddStatement { scope } => {
                AgentCommand::AddStatement(AgentStatementMutationArgs {
                    runtime,
                    inside_scope: scope.clone(),
                    at: AgentStatementAnchor::BodyEnd,
                    content_file: content_file()?,
                    mutation,
                })
            }
            Self::Replace { symbol } => {
                AgentCommand::ReplaceDeclaration(AgentReplaceDeclarationArgs {
                    runtime,
                    symbol: Some(symbol.clone()),
                    selector_handle: None,
                    content_file: content_file()?,
                    kind: None,
                    file_hint: None,
                    containing_type: None,
                    mutation,
                })
            }
        })
    }
}
