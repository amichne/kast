fn execute_agent_callers(args: AgentCallsArgs) -> AgentEnvelope {
    execute_agent_call_relationship(
        "agent/callers",
        "callers",
        "CALLER",
        "INCOMING",
        args.runtime,
        args.selector,
        args.depth.get(),
        args.limit.get(),
        args.page_token,
        args.view,
    )
}

fn execute_agent_callees(args: AgentCallsArgs) -> AgentEnvelope {
    execute_agent_call_relationship(
        "agent/callees",
        "callees",
        "CALLEE",
        "OUTGOING",
        args.runtime,
        args.selector,
        args.depth.get(),
        args.limit.get(),
        args.page_token,
        args.view,
    )
}

#[allow(clippy::too_many_arguments)]
fn execute_agent_call_relationship(
    public_method: &str,
    relation: &'static str,
    record_relation: &'static str,
    direction: &'static str,
    runtime: AgentRuntimeArgs,
    selector: AgentReusableSymbolSelectorArgs,
    depth: u8,
    limit: u8,
    page_token: Option<AgentRelationPageToken>,
    view: AgentRelationViewArgs,
) -> AgentEnvelope {
    let prepared = match prepare_reusable_selector(public_method, &runtime, selector) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let fingerprint = prepared.traversal_fingerprint(
        relation,
        direction,
        Some(depth),
        limit,
    );
    let page_handle = match page_token.as_ref() {
        Some(token) => match decode_traversal_page_token(token, relation, &fingerprint) {
            Ok(handle) => Some(handle),
            Err(error) => return error_envelope(public_method.to_string(), None, error),
        },
        None => None,
    };
    let AgentPreparedReusableSelector {
        selector,
        selector_handle,
        expected,
        ..
    } = prepared;
    let request = json_rpc_request(
        "symbol/callers",
        drop_nulls(json!({
            "selector": selector,
            "selectorHandle": selector_handle,
            "direction": direction.to_ascii_lowercase(),
            "depth": depth,
            "maxResults": limit,
            "pageToken": page_handle,
        })),
    );
    let envelope = wrap_traversal_page_token(
        execute_request(AgentRequest {
            method: "symbol/callers".to_string(),
            request: request.clone(),
            runtime,
            full_response: true,
            operation: AgentOperation::ReadOnly,
        }),
        request,
        relation,
        &fingerprint,
    );
    project_typed_call_relationship_envelope(
        public_method.to_string(),
        envelope,
        expected,
        relation,
        record_relation,
        usize::from(limit),
        usize::from(depth),
        AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count),
    )
}

fn execute_agent_implementations(args: AgentImplementationsArgs) -> AgentEnvelope {
    let prepared = match prepare_reusable_selector(
        "agent/implementations",
        &args.runtime,
        args.selector,
    ) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let fingerprint = prepared.traversal_fingerprint(
        "implementations",
        "",
        None,
        args.limit.get(),
    );
    let page_handle = match args.page_token.as_ref() {
        Some(token) => match decode_traversal_page_token(
            token,
            "implementations",
            &fingerprint,
        ) {
            Ok(handle) => Some(handle),
            Err(error) => return error_envelope("agent/implementations".to_string(), None, error),
        },
        None => None,
    };
    let AgentPreparedReusableSelector {
        selector,
        selector_handle,
        expected,
        ..
    } = prepared;
    let request = json_rpc_request(
        "symbol/implementations",
        drop_nulls(json!({
            "selector": selector,
            "selectorHandle": selector_handle,
            "maxResults": args.limit.get(),
            "pageToken": page_handle,
        })),
    );
    let envelope = wrap_traversal_page_token(
        execute_request(AgentRequest {
            method: "symbol/implementations".to_string(),
            request: request.clone(),
            runtime: args.runtime,
            full_response: true,
            operation: AgentOperation::ReadOnly,
        }),
        request,
        "implementations",
        &fingerprint,
    );
    project_typed_implementations_envelope(
        "agent/implementations".to_string(),
        envelope,
        expected,
        usize::from(args.limit.get()),
        AgentResultView::from_parts(
            args.view.verbose,
            args.view.explain,
            &args.view.fields,
            args.view.count,
        ),
    )
}

fn execute_agent_hierarchy(args: AgentHierarchyArgs) -> AgentEnvelope {
    let direction = match args.direction {
        AgentHierarchyDirection::Supertypes => "SUPERTYPES",
        AgentHierarchyDirection::Subtypes => "SUBTYPES",
        AgentHierarchyDirection::Both => "BOTH",
    };
    let prepared = match prepare_reusable_selector(
        "agent/hierarchy",
        &args.runtime,
        args.selector,
    ) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let fingerprint = prepared.traversal_fingerprint(
        "hierarchy",
        direction,
        Some(args.depth.get()),
        args.limit.get(),
    );
    let page_handle = match args.page_token.as_ref() {
        Some(token) => match decode_traversal_page_token(token, "hierarchy", &fingerprint) {
            Ok(handle) => Some(handle),
            Err(error) => return error_envelope("agent/hierarchy".to_string(), None, error),
        },
        None => None,
    };
    let AgentPreparedReusableSelector {
        selector,
        selector_handle,
        expected,
        ..
    } = prepared;
    let request = json_rpc_request(
        "symbol/hierarchy",
        drop_nulls(json!({
            "selector": selector,
            "selectorHandle": selector_handle,
            "direction": direction,
            "depth": args.depth.get(),
            "maxResults": args.limit.get(),
            "pageToken": page_handle,
        })),
    );
    let envelope = wrap_traversal_page_token(
        execute_request(AgentRequest {
            method: "symbol/hierarchy".to_string(),
            request: request.clone(),
            runtime: args.runtime,
            full_response: true,
            operation: AgentOperation::ReadOnly,
        }),
        request,
        "hierarchy",
        &fingerprint,
    );
    project_typed_hierarchy_envelope(
        "agent/hierarchy".to_string(),
        envelope,
        expected,
        direction,
        usize::from(args.limit.get()),
        usize::from(args.depth.get()),
        AgentResultView::from_parts(
            args.view.verbose,
            args.view.explain,
            &args.view.fields,
            args.view.count,
        ),
    )
}
