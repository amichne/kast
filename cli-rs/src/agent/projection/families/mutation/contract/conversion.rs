impl TryFrom<AgentMutationProjectionInput> for AgentMutationProjection {
    type Error = String;

    fn try_from(input: AgentMutationProjectionInput) -> std::result::Result<Self, Self::Error> {
        match input {
            AgentMutationProjectionInput::Plan(plan) => {
                let plan = *plan;
                if !matches!(
                    plan.result_type.as_str(),
                    "KAST_AGENT_MUTATION_PLAN"
                        | "KAST_AGENT_RENAME_PLAN"
                        | "KAST_AGENT_REPLACEMENT_PLAN"
                        | "KAST_AGENT_ADDITION_PLAN"
                ) || !plan.apply_required
                {
                    return Err("mutation plan did not require explicit apply".to_string());
                }
                let preview = match plan.result_type.as_str() {
                    "KAST_AGENT_RENAME_PLAN" if plan.request.method == "symbol/rename" => {
                        let AgentMutationPlanPreview::Rename(preview) =
                            plan.preview.ok_or_else(|| {
                                "rename plan omitted its exact compiler proof preview".to_string()
                            })?
                        else {
                            return Err(
                                "rename plan carried a non-rename proof preview".to_string()
                            );
                        };
                        preview.validate()?;
                        Some(AgentMutationPlanPreview::Rename(preview))
                    }
                    "KAST_AGENT_RENAME_PLAN" => {
                        return Err("rename plan used a non-rename request".to_string());
                    }
                    "KAST_AGENT_REPLACEMENT_PLAN"
                        if plan.request.method == "symbol/replace-declaration" =>
                    {
                        let AgentMutationPlanPreview::Replacement(preview) =
                            plan.preview.ok_or_else(|| {
                                "replacement plan omitted its exact compiler proof preview"
                                    .to_string()
                            })?
                        else {
                            return Err("replacement plan carried a non-replacement proof preview"
                                .to_string());
                        };
                        preview.validate()?;
                        Some(AgentMutationPlanPreview::Replacement(preview))
                    }
                    "KAST_AGENT_REPLACEMENT_PLAN" => {
                        return Err("replacement plan used a non-replacement request".to_string());
                    }
                    "KAST_AGENT_ADDITION_PLAN"
                        if plan.request.method == "symbol/add-file"
                            && plan.plan_kind == Some(AgentAdditionPlanKind::AddFile) =>
                    {
                        let AgentMutationPlanPreview::AddFile(preview) =
                            plan.preview.ok_or_else(|| {
                                "add-file plan omitted its exact compiler proof preview"
                                    .to_string()
                            })?
                        else {
                            return Err(
                                "add-file plan carried a non-add-file proof preview".to_string()
                            );
                        };
                        let target = preview.proof.target_path.clone();
                        let proposed = preview.proposed_content.clone();
                        preview.validate_for(&target, &proposed)?;
                        Some(AgentMutationPlanPreview::AddFile(preview))
                    }
                    "KAST_AGENT_ADDITION_PLAN" => {
                        return Err("addition plan kind disagreed with its request".to_string());
                    }
                    "KAST_AGENT_MUTATION_PLAN" if plan.preview.is_none() => None,
                    "KAST_AGENT_MUTATION_PLAN" => {
                        return Err(
                            "generic mutation plan carried an exact proof preview".to_string()
                        );
                    }
                    _ => unreachable!("plan result type validated above"),
                };
                let AgentMutationPlanRequestInput { method, params } = plan.request;
                let inside_file = params
                    .placement
                    .as_ref()
                    .and_then(|placement| placement.scope.inside_file())
                    .map(str::to_string);
                let file_path = params.file_path.or(inside_file);
                let mutation_kind = mutation_kind_from_method(&method);
                Ok(Self {
                    execution: AgentMutationExecutionProjection {
                        outcome: format!("PLANNED_{mutation_kind}"),
                        deduplicated: None,
                        failure: None,
                    },
                    plan: Some(AgentMutationPlanProjection {
                        method,
                        request_type: params.request_type,
                        symbol: params.symbol,
                        selector_handle: params.selector_handle,
                        new_name: params.new_name,
                        kind: params.kind,
                        file_hint: params.file_hint,
                        containing_type: params.containing_type,
                        file_path: file_path.clone(),
                        content_file: params.content_file,
                        placement: params.placement,
                        inside_scope: params.inside_scope,
                        anchor: params.statement_anchor.map(|anchor| {
                            AgentMutationPlanAnchorInput::AtAnchor {
                                anchor: anchor.canonical().to_string(),
                            }
                        }),
                        preview,
                    }),
                    edit_count: 0,
                    edits: Vec::new(),
                    files: file_path.into_iter().collect(),
                    diagnostics: AgentDiagnosticSeverityCounts {
                        error: 0,
                        warning: 0,
                        info: 0,
                        total: 0,
                    },
                })
            }
            AgentMutationProjectionInput::Execution(execution) => Self::from_execution(execution),
        }
    }
}
