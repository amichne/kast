fn json_rpc_request(method: &str, params: Value) -> Value {
    json!({
        "jsonrpc": "2.0",
        "method": method,
        "params": params,
        "id": 1
    })
}

fn empty_alias(method: &str, runtime: AgentRuntimeArgs) -> AliasParts {
    AliasParts {
        method: method.to_string(),
        params: json!({}),
        runtime,
    }
}

fn scaffold_alias(args: AgentScaffoldArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "targetFile", args.target_file);
    put_option_string(&mut params, "targetSymbol", args.target_symbol);
    put_option_enum(
        &mut params,
        "mode",
        args.mode.map(|value| value.canonical()),
    );
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    alias("symbol/scaffold", params, args.runtime)
}

fn discover_alias(args: AgentDiscoverArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "symbol", args.symbol);
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    put_option_string(&mut params, "fileHint", args.file_hint);
    put_option_u32(&mut params, "line", args.line);
    put_option_string(&mut params, "codeSnippet", args.code_snippet);
    put_option_string(&mut params, "containingType", args.containing_type);
    put_bool(
        &mut params,
        "includeDeclarationScope",
        args.include_declaration_scope,
    );
    put_option_u32(&mut params, "maxResults", args.max_results);
    alias("symbol/discover", params, args.runtime)
}

fn symbol_resolve_alias(args: AgentSymbolResolveArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "symbol", args.symbol);
    put_option_string(&mut params, "fileHint", args.file_hint);
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    put_option_string(&mut params, "containingType", args.containing_type);
    put_bool(
        &mut params,
        "includeDeclarationScope",
        args.include_declaration_scope,
    );
    put_bool(
        &mut params,
        "includeDocumentation",
        args.include_documentation,
    );
    put_option_u32(&mut params, "surroundingLines", args.surrounding_lines);
    put_bool(
        &mut params,
        "includeSurroundingMembers",
        args.include_surrounding_members,
    );
    alias("symbol/resolve", params, args.runtime)
}

fn symbol_references_alias(args: AgentSymbolReferencesArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "symbol", args.symbol);
    put_option_string(&mut params, "fileHint", args.file_hint);
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    put_option_string(&mut params, "containingType", args.containing_type);
    put_bool(&mut params, "includeDeclaration", args.include_declaration);
    alias("symbol/references", params, args.runtime)
}

fn symbol_callers_alias(args: AgentSymbolCallersArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "symbol", args.symbol);
    put_option_string(&mut params, "fileHint", args.file_hint);
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    put_option_string(&mut params, "containingType", args.containing_type);
    put_option_enum(
        &mut params,
        "direction",
        args.direction.map(|value| value.canonical()),
    );
    put_option_u32(&mut params, "depth", args.depth);
    put_option_u32(&mut params, "maxTotalCalls", args.max_total_calls);
    put_option_u32(
        &mut params,
        "maxChildrenPerNode",
        args.max_children_per_node,
    );
    put_option_u32(&mut params, "timeoutMillis", args.timeout_millis);
    alias("symbol/callers", params, args.runtime)
}

fn raw_resolve_alias(args: AgentRawResolveArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_bool(
        &mut params,
        "includeDeclarationScope",
        args.include_declaration_scope,
    );
    put_bool(
        &mut params,
        "includeDocumentation",
        args.include_documentation,
    );
    alias("raw/resolve", params, args.position.runtime)
}

fn raw_references_alias(args: AgentRawReferencesArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_bool(&mut params, "includeDeclaration", args.include_declaration);
    put_bool(
        &mut params,
        "includeUsageSiteScope",
        args.include_usage_site_scope,
    );
    alias("raw/references", params, args.position.runtime)
}

fn raw_call_hierarchy_alias(args: AgentRawCallHierarchyArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_string(
        &mut params,
        "direction",
        args.direction.canonical().to_string(),
    );
    put_option_u32(&mut params, "depth", args.depth);
    put_option_u32(&mut params, "maxTotalCalls", args.max_total_calls);
    put_option_u32(
        &mut params,
        "maxChildrenPerNode",
        args.max_children_per_node,
    );
    put_option_u32(&mut params, "timeoutMillis", args.timeout_millis);
    alias("raw/call-hierarchy", params, args.position.runtime)
}

fn raw_type_hierarchy_alias(args: AgentRawTypeHierarchyArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_option_enum(
        &mut params,
        "direction",
        args.direction.map(|value| value.canonical()),
    );
    put_option_u32(&mut params, "depth", args.depth);
    put_option_u32(&mut params, "maxResults", args.max_results);
    alias("raw/type-hierarchy", params, args.position.runtime)
}

fn raw_semantic_insertion_point_alias(args: AgentRawSemanticInsertionPointArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_string(&mut params, "target", args.target);
    alias(
        "raw/semantic-insertion-point",
        params,
        args.position.runtime,
    )
}

fn raw_rename_alias(args: AgentRawRenameArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_string(&mut params, "newName", args.new_name);
    put_bool(&mut params, "dryRun", args.dry_run);
    alias("raw/rename", params, args.position.runtime)
}

fn file_outline_alias(args: AgentFileOutlineArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "filePath", args.file_path);
    alias("raw/file-outline", params, args.runtime)
}

fn workspace_symbol_alias(args: AgentWorkspaceSymbolArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "pattern", args.pattern);
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    put_option_u32(&mut params, "maxResults", args.max_results);
    put_bool(&mut params, "regex", args.regex);
    put_bool(
        &mut params,
        "includeDeclarationScope",
        args.include_declaration_scope,
    );
    alias("raw/workspace-symbol", params, args.runtime)
}

fn workspace_search_alias(args: AgentWorkspaceSearchArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "pattern", args.pattern);
    put_bool(&mut params, "regex", args.regex);
    put_option_u32(&mut params, "maxResults", args.max_results);
    put_option_string(&mut params, "fileGlob", args.file_glob);
    put_bool(&mut params, "caseSensitive", args.case_sensitive);
    alias("raw/workspace-search", params, args.runtime)
}

fn workspace_files_alias(args: AgentWorkspaceFilesArgs) -> AliasParts {
    let mut params = Map::new();
    put_option_string(&mut params, "moduleName", args.module_name);
    put_bool(&mut params, "includeFiles", args.include_files);
    put_option_u32(&mut params, "maxFilesPerModule", args.max_files_per_module);
    alias("raw/workspace-files", params, args.runtime)
}

fn raw_implementations_alias(args: AgentRawImplementationsArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_option_u32(&mut params, "maxResults", args.max_results);
    alias("raw/implementations", params, args.position.runtime)
}

fn raw_code_actions_alias(args: AgentRawCodeActionsArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_option_string(&mut params, "diagnosticCode", args.diagnostic_code);
    alias("raw/code-actions", params, args.position.runtime)
}

fn raw_completions_alias(args: AgentRawCompletionsArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_option_u32(&mut params, "maxResults", args.max_results);
    if !args.kind_filter.is_empty() {
        params.insert("kindFilter".to_string(), json!(args.kind_filter));
    }
    alias("raw/completions", params, args.position.runtime)
}

fn file_paths_alias(method: &str, args: AgentFilePathsArgs) -> AliasParts {
    let mut params = Map::new();
    params.insert("filePaths".to_string(), json!(args.file_paths));
    alias(method, params, args.runtime)
}

fn optional_file_paths_alias(method: &str, args: AgentOptionalFilePathsArgs) -> AliasParts {
    let mut params = Map::new();
    if !args.file_paths.is_empty() {
        params.insert("filePaths".to_string(), json!(args.file_paths));
    }
    alias(method, params, args.runtime)
}

fn metrics_alias(args: AgentMetricsArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "metric", args.metric.canonical().to_string());
    put_option_u32(&mut params, "limit", args.limit);
    put_option_string(&mut params, "symbol", args.symbol);
    put_option_u32(&mut params, "depth", args.depth);
    put_option_string(&mut params, "fileGlob", args.file_glob);
    put_option_string(&mut params, "folderFilter", args.folder_filter);
    alias("database/metrics", params, args.runtime)
}

fn alias(method: &str, params: Map<String, Value>, runtime: AgentRuntimeArgs) -> AliasParts {
    AliasParts {
        method: method.to_string(),
        params: Value::Object(params),
        runtime,
    }
}

fn position_params(position: &AgentPositionArgs) -> Map<String, Value> {
    let mut params = Map::new();
    params.insert(
        "position".to_string(),
        json!({
            "filePath": position.file_path,
            "offset": position.offset,
        }),
    );
    params
}

fn put_string(params: &mut Map<String, Value>, key: &str, value: String) {
    params.insert(key.to_string(), json!(value));
}

fn put_option_string(params: &mut Map<String, Value>, key: &str, value: Option<String>) {
    if let Some(value) = value {
        params.insert(key.to_string(), json!(value));
    }
}

fn put_option_u32(params: &mut Map<String, Value>, key: &str, value: Option<u32>) {
    if let Some(value) = value {
        params.insert(key.to_string(), json!(value));
    }
}

fn put_option_enum(params: &mut Map<String, Value>, key: &str, value: Option<&'static str>) {
    if let Some(value) = value {
        params.insert(key.to_string(), json!(value));
    }
}

fn put_bool(params: &mut Map<String, Value>, key: &str, value: bool) {
    if value {
        params.insert(key.to_string(), json!(true));
    }
}
