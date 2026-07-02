fn execute_tools(args: AgentToolsArgs) -> AgentEnvelope {
    let result = if args.full {
        agent_tools_result().map(|result| serde_json::to_value(result).unwrap_or(Value::Null))
    } else {
        agent_tools_list_result().map(|result| serde_json::to_value(result).unwrap_or(Value::Null))
    };
    match result {
        Ok(result) => result_envelope("agent/tools".to_string(), result),
        Err(error) => error_envelope(
            "agent/tools".to_string(),
            None,
            AgentError::from_cli_error(error),
        ),
    }
}

fn agent_tools_list_result() -> Result<AgentToolsListResult> {
    let catalog = validate::embedded_catalog()?;
    let tools = agent_tool_rows(&catalog)?;
    Ok(AgentToolsListResult {
        result_type: "KAST_AGENT_TOOLS",
        catalog_sha256: manifest::sha256_bytes(validate::embedded_catalog_source().as_bytes()),
        count: tools.len(),
        invocation: agent_tool_invocation(),
        tools,
        help: vec![
            "Run `kast agent tools --full` for params schemas.".to_string(),
            "Run `kast agent call <method> --params-file <file> --workspace-root <repo>` to invoke a method.".to_string(),
        ],
        schema_version: SCHEMA_VERSION,
    })
}

fn agent_tools_result() -> Result<AgentToolsResult> {
    let catalog = validate::embedded_catalog()?;
    let tools = agent_tool_specs(&catalog)?;
    Ok(AgentToolsResult {
        result_type: "KAST_AGENT_TOOLS",
        catalog_sha256: manifest::sha256_bytes(validate::embedded_catalog_source().as_bytes()),
        tool_count: tools.len(),
        invocation: agent_tool_invocation(),
        tools,
        schema_version: SCHEMA_VERSION,
    })
}

fn agent_tool_invocation() -> AgentToolInvocation {
    AgentToolInvocation {
        command: "kast agent call",
        argv: vec![
            current_executable_argument(),
            "agent".to_string(),
            "call".to_string(),
            "<method>".to_string(),
        ],
        method_argument: "<method>",
        params_file_flag: "--params-file",
        workspace_root_flag: "--workspace-root",
    }
}

fn current_executable_argument() -> String {
    std::env::args_os()
        .next()
        .map(|arg| arg.to_string_lossy().into_owned())
        .filter(|arg| !arg.is_empty())
        .unwrap_or_else(|| "kast".to_string())
}

fn agent_tool_specs(catalog: &Value) -> Result<Vec<AgentToolSpec>> {
    let commands = catalog
        .get("commands")
        .and_then(Value::as_object)
        .ok_or_else(|| catalog_error("Command catalog must define a commands object."))?;
    let categories = catalog
        .get("categories")
        .and_then(Value::as_object)
        .ok_or_else(|| catalog_error("Command catalog must define a categories object."))?;
    let mut seen = BTreeSet::new();
    let mut tools = Vec::new();
    for category in TOOL_CATEGORY_ORDER {
        let Some(methods) = categories.get(*category).and_then(Value::as_array) else {
            continue;
        };
        for method in methods {
            let method = method.as_str().ok_or_else(|| {
                catalog_error(format!(
                    "Catalog category `{category}` contains a non-string method."
                ))
            })?;
            if seen.contains(method) {
                continue;
            }
            let Some(command) = commands.get(method) else {
                return Err(catalog_error(format!(
                    "Catalog category `{category}` references missing method `{method}`."
                )));
            };
            if command.get("tool").is_some() {
                tools.push(agent_tool_spec(catalog, method, command)?);
                seen.insert(method.to_string());
            }
        }
    }
    for (method, command) in commands {
        if command.get("tool").is_some() && !seen.contains(method) {
            tools.push(agent_tool_spec(catalog, method, command)?);
            seen.insert(method.clone());
        }
    }
    Ok(tools)
}

fn agent_tool_rows(catalog: &Value) -> Result<Vec<AgentToolRow>> {
    agent_tool_specs(catalog).map(|tools| {
        tools
            .into_iter()
            .map(|tool| AgentToolRow {
                name: tool.name,
                method: tool.method,
                category: tool.category,
                mutates: tool.mutates,
            })
            .collect()
    })
}

fn agent_tool_spec(catalog: &Value, method: &str, command: &Value) -> Result<AgentToolSpec> {
    let tool = command
        .get("tool")
        .and_then(Value::as_object)
        .ok_or_else(|| {
            catalog_error(format!(
                "Catalog command `{method}` must define tool metadata."
            ))
        })?;
    let name = tool.get("name").and_then(Value::as_str).ok_or_else(|| {
        catalog_error(format!(
            "Catalog command `{method}` tool.name must be a string."
        ))
    })?;
    let tool_description = tool
        .get("description")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            catalog_error(format!(
                "Catalog command `{method}` tool.description must be a string."
            ))
        })?;
    let category = command
        .get("category")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            catalog_error(format!(
                "Catalog command `{method}` category must be a string."
            ))
        })?;
    let request_schema = catalog_schema::request_schema(catalog, method)?;
    let parameters = request_schema
        .pointer("/properties/params")
        .cloned()
        .ok_or_else(|| {
            catalog_error(format!(
                "Generated schema for `{method}` is missing params."
            ))
        })?;
    Ok(AgentToolSpec {
        name: name.to_string(),
        method: method.to_string(),
        category: category.to_string(),
        description: agent_tool_description(method, command, tool_description),
        default_args: tool.get("defaultArgs").cloned(),
        parameters,
        mutates: agent_tool_mutates(method),
    })
}

fn agent_tool_description(method: &str, command: &Value, tool_description: &str) -> String {
    format!(
        "{} {}{}",
        agent_tool_policy_prefix(method),
        tool_description,
        agent_tool_variant_summary(command)
    )
}

fn agent_tool_policy_prefix(method: &str) -> &'static str {
    if method.starts_with("symbol/") {
        return "Preferred Kotlin funnel tool. Use this before raw file or offset operations when a symbol name, target type, or intended refactor is known.";
    }
    if method.starts_with("database/") {
        return "Preferred low-cost source-index tool. Use this before wide semantic traversal when index metrics can answer the question.";
    }
    if method.starts_with("raw/workspace-files") {
        return "Secondary workspace inspection tool. Use only after symbol/query, workspace symbols, or workspace search cannot identify a bounded target.";
    }
    if method.starts_with("raw/") {
        return "Bounded raw escape hatch. Use only with an exact file, offset, or explicit file list, or after the symbol-first path failed with a concrete blocker.";
    }
    "Kast system tool."
}

fn agent_tool_variant_summary(command: &Value) -> String {
    let Some(variants) = command.get("variants").and_then(Value::as_object) else {
        return String::new();
    };
    if variants.is_empty() {
        return String::new();
    }
    let variant_descriptions = variants
        .iter()
        .map(|(name, request)| {
            let required = request_required_fields(request)
                .into_iter()
                .filter(|field| field != "type")
                .collect::<Vec<_>>();
            format!(
                "{name} requires {}",
                if required.is_empty() {
                    "no extra fields".to_string()
                } else {
                    required.join(", ")
                }
            )
        })
        .collect::<Vec<_>>()
        .join("; ");
    format!(" Variant type values: {variant_descriptions}.")
}

fn request_required_fields(request: &Value) -> Vec<String> {
    if let Some(required) = request.get("required").and_then(Value::as_array) {
        return required
            .iter()
            .filter_map(Value::as_str)
            .map(str::to_string)
            .collect();
    }
    request
        .get("fields")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
        .filter(|(_, field)| field.get("optional").and_then(Value::as_bool) != Some(true))
        .map(|(name, _)| name.clone())
        .collect()
}

fn agent_tool_mutates(method: &str) -> bool {
    matches!(method, "symbol/rename" | "symbol/write-and-validate")
}

fn catalog_error(message: impl Into<String>) -> CliError {
    CliError::new("RPC_CATALOG_INVALID", message)
}
