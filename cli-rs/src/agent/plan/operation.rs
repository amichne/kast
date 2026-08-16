impl From<KastChangePlanCommand> for RequestedOperation {
    fn from(command: KastChangePlanCommand) -> Self {
        match command {
            KastChangePlanCommand::Rename {
                selector,
                name,
            } => Self::Rename {
                selector,
                new_name: name,
            },
            KastChangePlanCommand::AddFile { file } => Self::AddFile { path: file },
            KastChangePlanCommand::AddDeclaration { file } => {
                Self::AddDeclaration { path: file }
            }
            KastChangePlanCommand::Replace { selector } => Self::Replace { selector },
        }
    }
}

impl RequestedOperation {
    fn operation_id(&self) -> crate::agent::public_protocol::OperationId {
        match self {
            Self::Rename { .. } => crate::agent::public_protocol::OperationId::ChangePlanRename,
            Self::AddFile { .. } => {
                crate::agent::public_protocol::OperationId::ChangePlanAddFile
            }
            Self::AddDeclaration { .. } => {
                crate::agent::public_protocol::OperationId::ChangePlanAddDeclaration
            }
            Self::Replace { .. } => crate::agent::public_protocol::OperationId::ChangePlanReplace,
        }
    }

    fn prepare(
        self,
        workspace_root: PathBuf,
    ) -> std::result::Result<
        PreparedOperation,
        Box<crate::agent::public_protocol::ProtocolEnvelope>,
    > {
        use crate::agent::public_protocol::{
            MutationSelectorFamily, authenticate_mutation_selector,
        };

        match self {
            Self::Rename { selector, new_name } => authenticate_mutation_selector(
                workspace_root,
                selector,
                MutationSelectorFamily::Rename,
            )
            .map(|selector| PreparedOperation::Rename { selector, new_name }),
            Self::AddFile { path } => Ok(PreparedOperation::AddFile { path }),
            Self::AddDeclaration { path } => Ok(PreparedOperation::AddDeclaration { path }),
            Self::Replace { selector } => authenticate_mutation_selector(
                workspace_root,
                selector,
                MutationSelectorFamily::ReplaceDeclaration,
            )
            .map(|selector| PreparedOperation::Replace { selector }),
        }
    }
}

impl PreparedOperation {
    fn selector(&self) -> Option<&str> {
        match self {
            Self::Rename { selector, .. } | Self::Replace { selector } => {
                Some(selector.as_str())
            }
            Self::AddFile { .. } | Self::AddDeclaration { .. } => None,
        }
    }

    fn into_stored(self, preview: &Value) -> Result<StoredOperation> {
        match self {
            Self::Rename { .. } => AgentRenameAuthority::from_projected_result(preview)
                .map(|authority| StoredOperation::Rename {
                    authority: Box::new(authority),
                })
                .map_err(|message| CliError::new("KAST_INVALID_AGENT_RESULT", message)),
            Self::AddFile { .. } => AgentAddFileAuthority::from_projected_result(preview)
                .map(|authority| StoredOperation::AddFile {
                    authority: Box::new(authority),
                })
                .map_err(|message| CliError::new("KAST_INVALID_AGENT_RESULT", message)),
            Self::AddDeclaration { .. } => {
                AgentAddDeclarationAuthority::from_projected_result(preview)
                    .map(|authority| StoredOperation::AddDeclaration {
                        authority: Box::new(authority),
                    })
                    .map_err(|message| CliError::new("KAST_INVALID_AGENT_RESULT", message))
            }
            Self::Replace { .. } => AgentReplacementAuthority::from_projected_result(preview)
                .map(|authority| StoredOperation::Replace {
                    authority: Box::new(authority),
                })
                .map_err(|message| CliError::new("KAST_INVALID_AGENT_RESULT", message)),
        }
    }

    fn command(
        &self,
        workspace_root: PathBuf,
        content_file: Option<&Path>,
        apply: bool,
        idempotency_key: Option<String>,
    ) -> Result<AgentCommand> {
        let runtime = agent_adapter::agent_runtime(workspace_root);
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
            Self::Rename { selector, new_name } => AgentCommand::Rename(AgentRenameArgs {
                runtime,
                symbol: None,
                selector_handle: Some(parse_plan_selector(selector.as_str())?),
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
            Self::Replace { selector } => {
                AgentCommand::ReplaceDeclaration(AgentReplaceDeclarationArgs {
                    runtime,
                    symbol: None,
                    selector_handle: Some(parse_plan_selector(selector.as_str())?),
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

fn parse_plan_selector(value: &str) -> Result<AgentSelectorHandle> {
    value
        .parse()
        .map_err(|message| CliError::new("KAST_INVALID_AGENT_RESULT", message))
}

impl StoredOperation {
    fn name(&self) -> &'static str {
        match self {
            Self::Rename { .. } => "rename",
            Self::AddFile { .. } => "add-file",
            Self::AddDeclaration { .. } => "add-declaration",
            Self::Replace { .. } => "replace",
        }
    }

    fn requires_content(&self) -> bool {
        !matches!(self, Self::Rename { .. })
    }

    fn validate(&self) -> std::result::Result<(), String> {
        match self {
            Self::Rename { authority } => authority.validate(),
            Self::Replace { authority } => authority.validate(),
            Self::AddFile { authority } => authority.validate(),
            Self::AddDeclaration { authority } => authority.validate(),
        }
    }

    fn validate_content_sha256(
        &self,
        content_sha256: Option<&str>,
    ) -> std::result::Result<(), String> {
        match self {
            Self::Replace { authority }
                if content_sha256 == Some(authority.proposed_content_sha256()) =>
            {
                Ok(())
            }
            Self::Replace { .. } => {
                Err("replacement content digest disagreed with its exact proposed edit".to_string())
            }
            Self::Rename { .. } if content_sha256.is_none() => Ok(()),
            Self::Rename { .. } => Err("rename authority unexpectedly carried content".to_string()),
            Self::AddFile { authority }
                if content_sha256 == Some(authority.proposed_content_sha256()) =>
            {
                Ok(())
            }
            Self::AddFile { .. } => {
                Err("add-file content digest disagreed with its exact postimage".to_string())
            }
            Self::AddDeclaration { authority }
                if content_sha256 == Some(authority.proposed_content_sha256()) =>
            {
                Ok(())
            }
            Self::AddDeclaration { .. } => Err(
                "add-declaration content digest disagreed with its exact declaration".to_string(),
            ),
        }
    }

    fn validate_replacement_request_content(
        &self,
        content: Option<&[u8]>,
    ) -> std::result::Result<(), String> {
        let Self::Replace { authority } = self else {
            return Ok(());
        };
        let content = content
            .ok_or_else(|| "replacement authority omitted its submitted declaration".to_string())?;
        let proposed_declaration = std::str::from_utf8(content).map_err(|_| {
            "replacement authority submitted declaration was not exact UTF-8".to_string()
        })?;
        authority.validate_for_proposed_declaration(proposed_declaration)
    }

    fn transitions(&self, workspace_root: &Path) -> Result<Vec<ExactMutationTransition>> {
        let mut transitions = match self {
            Self::Rename { authority } => authority
                .file_images()
                .iter()
                .map(|image| transition_from_file_image(workspace_root, image))
                .collect::<Result<Vec<_>>>()?,
            Self::Replace { authority } => authority
                .file_images()
                .iter()
                .map(|image| transition_from_file_image(workspace_root, image))
                .collect::<Result<Vec<_>>>()?,
            Self::AddDeclaration { authority } => {
                vec![transition_from_file_image(workspace_root, authority.file_image())?]
            }
            Self::AddFile { authority } => {
                let absolute_path = authority.target_path().to_string();
                vec![ExactMutationTransition {
                    relative_path: relative_authority_path(workspace_root, &absolute_path)?,
                    absolute_path,
                    preimage: ExactMutationPreimage::Absent,
                    postimage: authority.postimage().clone(),
                }]
            }
        };
        transitions.sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
        validate_sorted_transition_set(workspace_root, &transitions)?;
        Ok(transitions)
    }

    fn postcondition_authority(&self) -> AgentMutationPostconditionAuthority {
        match self {
            Self::Rename { authority } => {
                AgentMutationPostconditionAuthority::Rename(authority.postcondition_authority())
            }
            Self::Replace { authority } => AgentMutationPostconditionAuthority::Replacement(
                authority.postcondition_authority(),
            ),
            Self::AddFile { authority } => AgentMutationPostconditionAuthority::AddFile(
                authority.postcondition_authority(),
            ),
            Self::AddDeclaration { authority } => {
                AgentMutationPostconditionAuthority::AddDeclaration(
                    authority.postcondition_authority(),
                )
            }
        }
    }

    fn minimum_postcondition_generation(&self) -> u64 {
        match self {
            Self::Rename { authority } => authority.minimum_postcondition_generation(),
            Self::Replace { authority } => authority.minimum_postcondition_generation(),
            Self::AddFile { authority } => authority.minimum_postcondition_generation(),
            Self::AddDeclaration { authority } => authority.minimum_postcondition_generation(),
        }
    }

    fn validate_postcondition_evidence(
        &self,
        evidence: &AgentMutationPostconditionEvidence,
    ) -> std::result::Result<(), String> {
        match (self, evidence) {
            (
                Self::Rename { authority },
                AgentMutationPostconditionEvidence::Rename(evidence),
            ) => authority.validate_postcondition_evidence(evidence),
            (
                Self::Replace { authority },
                AgentMutationPostconditionEvidence::Replacement(evidence),
            ) => authority.validate_postcondition_evidence(evidence),
            (
                Self::AddFile { authority },
                AgentMutationPostconditionEvidence::AddFile(evidence),
            ) => authority.validate_postcondition_evidence(evidence),
            (
                Self::AddDeclaration { authority },
                AgentMutationPostconditionEvidence::AddDeclaration(evidence),
            ) => authority.validate_postcondition_evidence(evidence),
            _ => Err(
                "mutation postcondition evidence used a different operation variant"
                    .to_string(),
            ),
        }
    }

    fn authority_bytes(&self) -> Result<Vec<u8>> {
        serde_json::to_vec(self).map_err(CliError::from)
    }

}

impl StoredPlan {
    fn set_runtime_output(
        &mut self,
        format: OutputFormat,
        operation: crate::agent::public_protocol::OperationId,
    ) {
        self.runtime_output = Some(plan_output_context(format, operation));
    }

    fn runtime_output(&self) -> Result<PlanOutputContext> {
        self.runtime_output.ok_or_else(|| {
            CliError::new(
                "KAST_MUTATION_OUTPUT_CONTEXT_MISSING",
                "The mutation result has no explicit output context.",
            )
        })
    }
}

fn transition_from_file_image(
    workspace_root: &Path,
    image: &AgentExactFileImage,
) -> Result<ExactMutationTransition> {
    let absolute_path = image.file_path().to_string();
    Ok(ExactMutationTransition {
        relative_path: relative_authority_path(workspace_root, &absolute_path)?,
        absolute_path,
        preimage: ExactMutationPreimage::Present {
            image: image.preimage().clone(),
        },
        postimage: image.postimage().clone(),
    })
}

fn relative_authority_path(workspace_root: &Path, absolute_path: &str) -> Result<String> {
    let absolute = Path::new(absolute_path);
    let relative = absolute.strip_prefix(workspace_root).map_err(|_| {
        CliError::new(
            "KAST_PLAN_INVALID",
            "The mutation authority contains a path outside its exact workspace root.",
        )
    })?;
    if relative.as_os_str().is_empty()
        || !relative
            .components()
            .all(|component| matches!(component, std::path::Component::Normal(_)))
    {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The mutation authority path is not canonical and workspace-relative.",
        ));
    }
    relative.to_str().map(str::to_string).ok_or_else(|| {
        CliError::new(
            "KAST_PLAN_INVALID",
            "The mutation authority path is not UTF-8.",
        )
    })
}
