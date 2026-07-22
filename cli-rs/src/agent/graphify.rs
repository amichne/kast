#[derive(Debug)]
struct GraphifyManifestScope {
    selected: Vec<PathBuf>,
    removed: Vec<PathBuf>,
    incremental: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct GraphifyRelationVariant {
    relation: String,
    context: Option<String>,
    resolved_target_key: Option<String>,
    count: usize,
    line: u64,
    source_file: String,
}

type GraphifyOccurrenceKey = (String, String, String, Option<String>, Option<String>);

fn execute_agent_graphify(mut args: AgentGraphifyArgs) -> AgentEnvelope {
    let workspace_root = match graphify_workspace_root(&args) {
        Ok(root) => root,
        Err(error) => return error_envelope("agent/graphify".to_string(), None, error),
    };
    args.runtime.workspace_root = Some(workspace_root.clone());
    let manifest = match read_graphify_json(&args.manifest, "manifest") {
        Ok(value) => value,
        Err(error) => return error_envelope("agent/graphify".to_string(), None, error),
    };
    let scope = match graphify_manifest_scope(&manifest, &workspace_root) {
        Ok(scope) => scope,
        Err(error) => return error_envelope("agent/graphify".to_string(), None, error),
    };
    if scope.incremental && args.base_graph.is_none() {
        return error_envelope(
            "agent/graphify".to_string(),
            None,
            agent_error(
                "FULL_REBUILD_REQUIRED",
                "Incremental Kotlin extraction requires --base-graph with Kast Graphify-v2 identities.",
            ),
        );
    }
    if scope.incremental && !scope.removed.is_empty() {
        return error_envelope(
            "agent/graphify".to_string(),
            None,
            agent_error(
                "FULL_REBUILD_REQUIRED",
                "Kotlin file deletions require one full Kast graph rebuild.",
            ),
        );
    }

    let semantic = if scope.selected.is_empty() {
        json!({"symbols": [], "boundarySymbols": [], "relations": [], "coverage": {"files": [], "omittedExternalTargetCount": 0}})
    } else {
        match graphify_semantic_pages(&args.runtime, &scope, usize::from(args.batch_size)) {
            Ok(result) => result,
            Err(envelope) => return *envelope,
        }
    };
    let fragment = match project_graphify_fragment(&semantic) {
        Ok(fragment) => fragment,
        Err(error) => return error_envelope("agent/graphify".to_string(), None, error),
    };

    if scope.incremental {
        let base_path = args.base_graph.as_ref().expect("checked above");
        let base = match read_graphify_json(base_path, "base graph") {
            Ok(value) => value,
            Err(error) => return error_envelope("agent/graphify".to_string(), None, error),
        };
        if let Err(error) = validate_graphify_incremental_base(&base, &fragment, &scope, &workspace_root) {
            return error_envelope("agent/graphify".to_string(), None, error);
        }
    }

    if let Err(error) = write_graphify_fragment_atomically(&args.output_file, &fragment) {
        return error_envelope("agent/graphify".to_string(), None, error);
    }
    result_envelope(
        "agent/graphify".to_string(),
        json!({
            "type": "KAST_AGENT_GRAPHIFY_RESULT",
            "outputFile": args.output_file,
            "nodeCount": fragment["nodes"].as_array().map_or(0, Vec::len),
            "edgeCount": fragment["edges"].as_array().map_or(0, Vec::len),
            "incremental": scope.incremental,
            "batchSize": args.batch_size,
            "coverage": semantic["coverage"],
        }),
    )
}

fn graphify_workspace_root(args: &AgentGraphifyArgs) -> std::result::Result<PathBuf, AgentError> {
    let raw = args.runtime.workspace_root.as_ref().ok_or_else(|| {
        agent_error("AGENT_USAGE", "--workspace-root is required for kast agent graphify.")
    })?;
    if !raw.is_absolute() {
        return Err(agent_error("AGENT_USAGE", "--workspace-root must be absolute."));
    }
    std::fs::canonicalize(raw).map_err(|error| {
        agent_error(
            "AGENT_USAGE",
            format!("Cannot resolve workspace root {}: {error}", raw.display()),
        )
    })
}

fn read_graphify_json(path: &Path, label: &str) -> std::result::Result<Value, AgentError> {
    let bytes = std::fs::read(path).map_err(|error| {
        agent_error(
            "AGENT_USAGE",
            format!("Cannot read {label} {}: {error}", path.display()),
        )
    })?;
    serde_json::from_slice(&bytes).map_err(|error| {
        agent_error(
            "AGENT_USAGE",
            format!("Cannot parse {label} {}: {error}", path.display()),
        )
    })
}

fn graphify_manifest_scope(
    manifest: &Value,
    workspace_root: &Path,
) -> std::result::Result<GraphifyManifestScope, AgentError> {
    let incremental = manifest["incremental"].as_bool().unwrap_or(false)
        || manifest.get("new_files").is_some()
        || manifest.get("changed_files").is_some()
        || manifest.get("deleted_files").is_some();
    let selected_values = if incremental {
        graphify_manifest_code_paths(&manifest["new_files"])
            .into_iter()
            .chain(graphify_manifest_code_paths(&manifest["changed_files"]))
            .collect::<Vec<_>>()
    } else {
        graphify_manifest_code_paths(&manifest["files"])
    };
    let removed_values = manifest["deleted_files"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(str::to_string)
        .collect::<Vec<_>>();
    let mut selected = selected_values
        .into_iter()
        .filter(|value| graphify_is_kotlin_path(Path::new(value)))
        .map(|value| graphify_admit_path(&value, workspace_root, true))
        .collect::<std::result::Result<Vec<_>, _>>()?;
    let mut removed = removed_values
        .into_iter()
        .filter(|value| graphify_is_kotlin_path(Path::new(value)))
        .map(|value| graphify_admit_path(&value, workspace_root, false))
        .collect::<std::result::Result<Vec<_>, _>>()?;
    selected.sort();
    selected.dedup();
    removed.sort();
    removed.dedup();
    Ok(GraphifyManifestScope {
        selected,
        removed,
        incremental,
    })
}

fn graphify_manifest_code_paths(section: &Value) -> Vec<String> {
    section["code"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(str::to_string)
        .collect()
}

fn graphify_is_kotlin_path(path: &Path) -> bool {
    path.extension().and_then(|value| value.to_str()) == Some("kt")
}

fn graphify_admit_path(
    raw: &str,
    workspace_root: &Path,
    must_exist: bool,
) -> std::result::Result<PathBuf, AgentError> {
    let path = PathBuf::from(raw);
    if !path.is_absolute() {
        return Err(agent_error(
            "AGENT_USAGE",
            format!("Graphify Kotlin paths must be absolute: {raw}"),
        ));
    }
    let admitted = if must_exist {
        std::fs::canonicalize(&path).map_err(|error| {
            agent_error("AGENT_USAGE", format!("Cannot resolve Kotlin path {raw}: {error}"))
        })?
    } else {
        graphify_lexically_normalize(&path)?
    };
    if !admitted.starts_with(workspace_root) {
        return Err(agent_error(
            "AGENT_USAGE",
            format!("Graphify Kotlin path is outside the workspace: {raw}"),
        ));
    }
    Ok(admitted)
}

fn graphify_lexically_normalize(path: &Path) -> std::result::Result<PathBuf, AgentError> {
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            Component::ParentDir => {
                if !normalized.pop() {
                    return Err(agent_error("AGENT_USAGE", "Kotlin path escapes its filesystem root."));
                }
            }
            Component::CurDir => {}
            component => normalized.push(component.as_os_str()),
        }
    }
    Ok(normalized)
}

fn graphify_semantic_pages(
    runtime: &AgentRuntimeArgs,
    scope: &GraphifyManifestScope,
    batch_size: usize,
) -> std::result::Result<Value, Box<AgentEnvelope>> {
    let capabilities = execute_request(AgentRequest {
        method: "capabilities".to_string(),
        request: json_rpc_request("capabilities", json!({})),
        runtime: runtime.clone(),
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    if !capabilities.ok {
        return Err(Box::new(capabilities));
    }
    let page_size = graphify_page_size_from_capabilities(
        capabilities
            .result
            .as_ref()
            .expect("successful capabilities RPC has a result"),
    )
    .map_err(|error| {
        Box::new(error_envelope(
            "agent/graphify".to_string(),
            None,
            error,
        ))
    })?;
    let workspace_root = runtime
        .workspace_root
        .as_deref()
        .expect("Graphify workspace root was validated before semantic extraction");
    let expected_hashes = graphify_capture_file_hashes(&scope.selected, workspace_root).map_err(|error| {
        Box::new(error_envelope(
            "agent/graphify".to_string(),
            None,
            error,
        ))
    })?;
    let batch_count = scope.selected.len().div_ceil(batch_size);
    let mut symbols = BTreeMap::new();
    let mut boundary_symbols = BTreeMap::new();
    let mut relations = BTreeMap::new();
    let mut coverage_files = BTreeMap::new();
    let mut omitted_external_target_count = 0_u64;
    for (batch_index, batch) in scope.selected.chunks(batch_size).enumerate() {
        let result = graphify_semantic_batch(runtime, batch, page_size, batch_index, batch_count)?;
        graphify_validate_batch_coverage(&result["coverage"], batch, workspace_root, &expected_hashes)
            .map_err(|error| {
                Box::new(error_envelope(
                    "agent/graphify".to_string(),
                    None,
                    error,
                ))
            })?;
        graphify_verify_current_file_hashes(batch, workspace_root, &expected_hashes).map_err(
            |error| {
                Box::new(error_envelope(
                    "agent/graphify".to_string(),
                    None,
                    error,
                ))
            },
        )?;
        for symbol in result["symbols"].as_array().into_iter().flatten() {
            let key = graphify_required_str(symbol, "canonicalKey").map_err(|error| {
                Box::new(error_envelope(
                    "agent/graphify".to_string(),
                    None,
                    error,
                ))
            })?;
            symbols.insert(key.to_string(), symbol.clone());
        }
        for symbol in result["boundarySymbols"].as_array().into_iter().flatten() {
            let key = graphify_required_str(symbol, "canonicalKey").map_err(|error| {
                Box::new(error_envelope(
                    "agent/graphify".to_string(),
                    None,
                    error,
                ))
            })?;
            boundary_symbols.insert(key.to_string(), symbol.clone());
        }
        for relation in result["relations"].as_array().into_iter().flatten() {
            relations.insert(relation.to_string(), relation.clone());
        }
        for file in result["coverage"]["files"].as_array().into_iter().flatten() {
            let path = graphify_required_str(file, "path").map_err(|error| {
                Box::new(error_envelope(
                    "agent/graphify".to_string(),
                    None,
                    error,
                ))
            })?;
            coverage_files.insert(path.to_string(), file.clone());
        }
        let omitted = result["coverage"]["omittedExternalTargetCount"]
            .as_u64()
            .ok_or_else(|| {
                Box::new(error_envelope(
                    "agent/graphify".to_string(),
                    None,
                    agent_error(
                        "SEMANTIC_GRAPH_INVALID",
                        "Semantic graph coverage omitted omittedExternalTargetCount.",
                    ),
                ))
            })?;
        omitted_external_target_count = omitted_external_target_count.checked_add(omitted).ok_or_else(|| {
            Box::new(error_envelope(
                "agent/graphify".to_string(),
                None,
                agent_error("SEMANTIC_GRAPH_INVALID", "Semantic graph omission count overflowed."),
            ))
        })?;
    }
    graphify_verify_current_file_hashes(&scope.selected, workspace_root, &expected_hashes).map_err(
        |error| {
            Box::new(error_envelope(
                "agent/graphify".to_string(),
                None,
                error,
            ))
        },
    )?;
    boundary_symbols.retain(|key, _| !symbols.contains_key(key));
    Ok(json!({
        "symbols": symbols.into_values().collect::<Vec<_>>(),
        "boundarySymbols": boundary_symbols.into_values().collect::<Vec<_>>(),
        "relations": relations.into_values().collect::<Vec<_>>(),
        "coverage": {
            "files": coverage_files.into_values().collect::<Vec<_>>(),
            "omittedExternalTargetCount": omitted_external_target_count,
        },
    }))
}

fn graphify_semantic_batch(
    runtime: &AgentRuntimeArgs,
    batch: &[PathBuf],
    page_size: u64,
    batch_index: usize,
    batch_count: usize,
) -> std::result::Result<Value, Box<AgentEnvelope>> {
    let mut symbols = Vec::new();
    let mut boundary_symbols = Vec::new();
    let mut relations = Vec::new();
    let mut continuation: Option<String> = None;
    let coverage = loop {
        let params = drop_nulls(json!({
            "filePaths": batch,
            "removedFilePaths": [],
            "pageSize": page_size,
            "continuation": continuation,
        }));
        let envelope = execute_request(AgentRequest {
            method: "raw/semantic-graph".to_string(),
            request: json_rpc_request("raw/semantic-graph", params),
            runtime: runtime.clone(),
            full_response: true,
            operation: AgentOperation::ReadOnly,
        });
        if !envelope.ok {
            return Err(graphify_batch_failure(envelope, batch_index, batch_count, batch.len()));
        }
        let result = envelope.result.as_ref().expect("successful RPC has a result");
        symbols.extend(result["symbols"].as_array().cloned().unwrap_or_default());
        boundary_symbols.extend(
            result["boundarySymbols"]
                .as_array()
                .cloned()
                .unwrap_or_default(),
        );
        relations.extend(result["relations"].as_array().cloned().unwrap_or_default());
        let next = result["nextPageToken"].as_str().map(str::to_string);
        if next.is_none() {
            break result["coverage"].clone();
        }
        continuation = next;
    };
    Ok(json!({
        "symbols": symbols,
        "boundarySymbols": boundary_symbols,
        "relations": relations,
        "coverage": coverage,
    }))
}

fn graphify_batch_failure(
    mut envelope: AgentEnvelope,
    batch_index: usize,
    batch_count: usize,
    batch_size: usize,
) -> Box<AgentEnvelope> {
    if let Some(error) = envelope.error.as_mut() {
        error.message = format!(
            "Semantic graph batch {}/{} ({} Kotlin files) failed: {}",
            batch_index + 1,
            batch_count,
            batch_size,
            error.message,
        );
        error.details.insert("batchIndex".to_string(), json!(batch_index + 1));
        error.details.insert("batchCount".to_string(), json!(batch_count));
        error.details.insert("batchSize".to_string(), json!(batch_size));
    }
    Box::new(envelope)
}

fn graphify_capture_file_hashes(
    selected: &[PathBuf],
    workspace_root: &Path,
) -> std::result::Result<BTreeMap<String, String>, AgentError> {
    selected
        .iter()
        .map(|path| {
            Ok((
                graphify_relative_source_path(path, workspace_root)?,
                graphify_file_hash(path)?,
            ))
        })
        .collect()
}

fn graphify_validate_batch_coverage(
    coverage: &Value,
    batch: &[PathBuf],
    workspace_root: &Path,
    expected_hashes: &BTreeMap<String, String>,
) -> std::result::Result<(), AgentError> {
    let files = coverage["files"].as_array().ok_or_else(|| {
        agent_error(
            "SEMANTIC_GRAPH_INVALID",
            "Semantic graph coverage omitted its files array.",
        )
    })?;
    let covered = files
        .iter()
        .map(|file| {
            let path = graphify_required_str(file, "path")?;
            let actual_hash = graphify_required_str(file, "contentHash")?;
            let expected_hash = expected_hashes.get(path).ok_or_else(|| {
                agent_error(
                    "SEMANTIC_GRAPH_INVALID",
                    format!("Semantic graph coverage returned an unrequested file: {path}"),
                )
            })?;
            if actual_hash != expected_hash {
                return Err(agent_error(
                    "WORKSPACE_CHANGED",
                    format!("Kotlin file changed while semantic extraction was running: {path}"),
                ));
            }
            Ok(path.to_string())
        })
        .collect::<std::result::Result<BTreeSet<_>, _>>()?;
    for path in batch {
        let relative = graphify_relative_source_path(path, workspace_root)?;
        if !covered.contains(&relative) {
            return Err(agent_error(
                "SEMANTIC_GRAPH_INVALID",
                format!("Semantic graph coverage omitted requested file: {relative}"),
            ));
        }
    }
    Ok(())
}

fn graphify_verify_current_file_hashes(
    selected: &[PathBuf],
    workspace_root: &Path,
    expected_hashes: &BTreeMap<String, String>,
) -> std::result::Result<(), AgentError> {
    for path in selected {
        let relative = graphify_relative_source_path(path, workspace_root)?;
        let expected = expected_hashes
            .get(&relative)
            .expect("captured Graphify file has an expected hash");
        if graphify_file_hash(path)? != *expected {
            return Err(agent_error(
                "WORKSPACE_CHANGED",
                format!("Kotlin file changed while semantic extraction was running: {relative}"),
            ));
        }
    }
    Ok(())
}

fn graphify_relative_source_path(
    path: &Path,
    workspace_root: &Path,
) -> std::result::Result<String, AgentError> {
    path.strip_prefix(workspace_root)
        .map(|relative| relative.to_string_lossy().replace('\\', "/"))
        .map_err(|_| {
            agent_error(
                "AGENT_USAGE",
                format!("Graphify Kotlin path is outside the workspace: {}", path.display()),
            )
        })
}

fn graphify_file_hash(path: &Path) -> std::result::Result<String, AgentError> {
    use sha2::{Digest, Sha256};

    let bytes = std::fs::read(path).map_err(|error| {
        agent_error(
            "WORKSPACE_CHANGED",
            format!("Cannot read Kotlin file {} during extraction: {error}", path.display()),
        )
    })?;
    Ok(hex::encode(Sha256::digest(bytes)))
}

fn graphify_page_size_from_capabilities(
    capabilities: &Value,
) -> std::result::Result<u64, AgentError> {
    let advertised = capabilities["limits"]["maxResults"]
        .as_u64()
        .filter(|value| *value > 0)
        .ok_or_else(|| {
            agent_error(
                "SEMANTIC_GRAPH_INVALID",
                "Backend capabilities omitted a positive limits.maxResults value.",
            )
        })?;
    Ok(advertised.min(500))
}

#[derive(Debug, Clone)]
struct GraphifyCanonicalIdentity {
    fq_name: Option<String>,
    signature: Option<String>,
    kind: String,
    source_file: String,
    start_offset: u64,
    end_offset: u64,
    line: u64,
}

#[derive(Debug)]
struct GraphifyNodeGroup {
    label: String,
    source_file: String,
    line: u64,
    expanded: bool,
    identities: BTreeMap<String, GraphifyCanonicalIdentity>,
}

fn project_graphify_fragment(semantic: &Value) -> std::result::Result<Value, AgentError> {
    let symbols = semantic["symbols"].as_array().ok_or_else(|| {
        agent_error("SEMANTIC_GRAPH_INVALID", "Semantic graph response omitted symbols.")
    })?;
    let boundary_symbols = semantic["boundarySymbols"].as_array().ok_or_else(|| {
        agent_error(
            "SEMANTIC_GRAPH_INVALID",
            "Semantic graph response omitted boundarySymbols.",
        )
    })?;
    let mut symbol_records = BTreeMap::new();
    for (records, expanded) in [(boundary_symbols, false), (symbols, true)] {
        for symbol in records {
            if symbol["kind"] == "CONSTRUCTOR" {
                continue;
            }
            let canonical_key = graphify_required_str(symbol, "canonicalKey")?;
            symbol_records.insert(canonical_key.to_string(), (symbol.clone(), expanded));
        }
    }
    let mut ids = BTreeMap::new();
    for (canonical_key, (symbol, _)) in &symbol_records {
        ids.insert(
            canonical_key.clone(),
            graphify_native_symbol_id(symbol, &symbol_records)?,
        );
    }
    let mut groups: BTreeMap<String, GraphifyNodeGroup> = BTreeMap::new();
    for (canonical_key, (symbol, expanded)) in &symbol_records {
        let id = ids
            .get(canonical_key)
            .expect("every Graphify symbol has a projected id")
            .clone();
        let label = graphify_required_str(symbol, "name")?.to_string();
        let source_file = graphify_required_str(symbol, "path")?.to_string();
        let line = graphify_required_u64(symbol, "line")?;
        let group = groups.entry(id).or_insert_with(|| GraphifyNodeGroup {
            label: label.clone(),
            source_file: source_file.clone(),
            line,
            expanded: false,
            identities: BTreeMap::new(),
        });
        if (line, source_file.as_str(), canonical_key.as_str()) <
            (group.line, group.source_file.as_str(), group.identities.keys().next().map_or("", String::as_str))
        {
            group.label = label;
            group.source_file.clone_from(&source_file);
            group.line = line;
        }
        group.expanded |= *expanded;
        group.identities.insert(
            canonical_key.clone(),
            GraphifyCanonicalIdentity {
                fq_name: symbol["fqName"].as_str().map(str::to_string),
                signature: symbol["signature"].as_str().map(str::to_string),
                kind: graphify_required_str(symbol, "kind")?.to_string(),
                source_file,
                start_offset: graphify_required_u64(symbol, "startOffset")?,
                end_offset: graphify_required_u64(symbol, "endOffset")?,
                line,
            },
        );
    }
    let nodes = groups
        .into_iter()
        .map(|(id, group)| {
            let canonical_keys = group.identities.keys().cloned().collect::<Vec<_>>();
            let fq_names = group
                .identities
                .values()
                .filter_map(|identity| identity.fq_name.clone())
                .collect::<BTreeSet<_>>();
            let signatures = group
                .identities
                .values()
                .filter_map(|identity| identity.signature.clone())
                .collect::<BTreeSet<_>>();
            let kotlin_kinds = group
                .identities
                .values()
                .map(|identity| identity.kind.clone())
                .collect::<BTreeSet<_>>();
            let exact_ranges = group
                .identities
                .iter()
                .map(|(canonical_key, identity)| {
                    json!({
                        "canonical_key": canonical_key,
                        "source_file": identity.source_file,
                        "start_offset": identity.start_offset,
                        "end_offset": identity.end_offset,
                        "line": identity.line,
                    })
                })
                .collect::<Vec<_>>();
            let mut metadata = json!({
                "canonical_keys": canonical_keys,
                "fq_names": fq_names,
                "signatures": signatures,
                "kotlin_kinds": kotlin_kinds,
                "provider": "kast-k2",
                "id_schema_version": "kast:graphify:v2",
                "exact_ranges": exact_ranges,
            });
            if !group.expanded {
                metadata["boundary"] = Value::String("unexpanded".to_string());
            }
            json!({
                "id": id,
                "label": group.label,
                "file_type": "code",
                "source_file": group.source_file,
                "source_location": format!("L{}", group.line),
                "_origin": "ast",
                "metadata": metadata,
            })
        })
        .collect::<Vec<_>>();

    let relations = semantic["relations"].as_array().ok_or_else(|| {
        agent_error("SEMANTIC_GRAPH_INVALID", "Semantic graph response omitted relations.")
    })?;
    let mut occurrences: BTreeMap<GraphifyOccurrenceKey, GraphifyRelationVariant> = BTreeMap::new();
    for relation in relations {
        let source_key = graphify_required_str(relation, "sourceKey")?;
        let target_key = graphify_required_str(relation, "targetKey")?;
        let source = ids.get(source_key).cloned().ok_or_else(|| {
            agent_error(
                "SEMANTIC_GRAPH_INVALID",
                format!("Semantic graph relation source has no projected node: {source_key}"),
            )
        })?;
        let target = ids
            .get(target_key)
            .cloned()
            .ok_or_else(|| {
                agent_error(
                    "SEMANTIC_GRAPH_INVALID",
                    format!("Semantic graph relation target has no selected or boundary node: {target_key}"),
                )
            })?;
        let relation_name = graphify_required_str(relation, "kind")?.to_ascii_lowercase();
        let context = relation["context"]
            .as_str()
            .filter(|value| *value != "NONE")
            .map(|value| value.to_ascii_lowercase());
        let resolved_target_key = relation["resolvedTargetKey"].as_str().map(str::to_string);
        let line = relation["line"].as_u64().unwrap_or(1);
        let source_file = graphify_required_str(relation, "sourcePath")?.to_string();
        let key = (
            source,
            target,
            relation_name.clone(),
            context.clone(),
            resolved_target_key.clone(),
        );
        occurrences
            .entry(key)
            .and_modify(|variant| {
                variant.count += 1;
                if line < variant.line {
                    variant.line = line;
                    variant.source_file.clone_from(&source_file);
                }
            })
            .or_insert(GraphifyRelationVariant {
                relation: relation_name,
                context,
                resolved_target_key,
                count: 1,
                line,
                source_file,
            });
    }

    let mut endpoint_variants: BTreeMap<(String, String), Vec<GraphifyRelationVariant>> = BTreeMap::new();
    for ((source, target, _, _, _), variant) in occurrences {
        endpoint_variants.entry((source, target)).or_default().push(variant);
    }
    let mut edges = Vec::new();
    for ((source, target), mut variants) in endpoint_variants {
        variants.sort();
        let winner = variants.last().expect("endpoint variants are non-empty");
        let collapsed = variants
            .iter()
            .map(|variant| json!({
                "relation": variant.relation,
                "context": variant.context,
                "resolved_target_key": variant.resolved_target_key,
                "count": variant.count,
                "source_location": format!("L{}", variant.line),
            }))
            .collect::<Vec<_>>();
        let mut edge = json!({
            "source": source,
            "target": target,
            "relation": winner.relation,
            "confidence": "EXTRACTED",
            "confidence_score": 1.0,
            "source_file": winner.source_file,
            "source_location": format!("L{}", winner.line),
            "weight": 1.0,
            "_origin": "ast",
            "metadata": {
                "collapsed_relations": collapsed,
            },
        });
        if let Some(context) = &winner.context {
            edge["context"] = Value::String(context.clone());
        }
        edges.push(edge);
    }
    Ok(json!({
        "nodes": nodes,
        "edges": edges,
        "hyperedges": [],
        "input_tokens": 0,
        "output_tokens": 0,
    }))
}

fn graphify_required_str<'a>(value: &'a Value, field: &str) -> std::result::Result<&'a str, AgentError> {
    value[field].as_str().ok_or_else(|| {
        agent_error(
            "SEMANTIC_GRAPH_INVALID",
            format!("Semantic graph record omitted string field {field}."),
        )
    })
}

fn graphify_required_u64(value: &Value, field: &str) -> std::result::Result<u64, AgentError> {
    value[field].as_u64().ok_or_else(|| {
        agent_error(
            "SEMANTIC_GRAPH_INVALID",
            format!("Semantic graph record omitted unsigned integer field {field}."),
        )
    })
}

fn graphify_native_symbol_id(
    symbol: &Value,
    symbols: &BTreeMap<String, (Value, bool)>,
) -> std::result::Result<String, AgentError> {
    let kind = graphify_required_str(symbol, "kind")?;
    let path = graphify_required_str(symbol, "path")?;
    let name = graphify_required_str(symbol, "name")?;
    let stem = graphify_file_stem(path);
    match kind {
        "FILE" => Ok(graphify_make_id(&[stem.as_str()])),
        "CLASS" | "INTERFACE" | "OBJECT" | "ENUM_CLASS" => {
            Ok(graphify_make_id(&[stem.as_str(), name]))
        }
        "FUNCTION" => Ok(graphify_make_id(&[stem.as_str(), name])),
        "MEMBER_FUNCTION" | "ENUM_ENTRY" => {
            let owner_id = symbol["ownerKey"]
                .as_str()
                .and_then(|owner_key| symbols.get(owner_key))
                .map(|(owner, _)| {
                    let owner_path = graphify_required_str(owner, "path")?;
                    let owner_name = graphify_required_str(owner, "name")?;
                    let owner_stem = graphify_file_stem(owner_path);
                    Ok(graphify_make_id(&[owner_stem.as_str(), owner_name]))
                })
                .transpose()?
                .or_else(|| {
                    symbol["fqName"].as_str().and_then(|fq_name| {
                        fq_name
                            .rsplit_once('.')
                            .and_then(|(owner, _)| owner.rsplit('.').next())
                            .map(|owner_name| graphify_make_id(&[stem.as_str(), owner_name]))
                    })
                })
                .ok_or_else(|| {
                    agent_error(
                        "SEMANTIC_GRAPH_INVALID",
                        format!("Semantic graph member has no projectable owner: {name}"),
                    )
                })?;
            Ok(graphify_make_id(&[owner_id.as_str(), name]))
        }
        other => Err(agent_error(
            "SEMANTIC_GRAPH_INVALID",
            format!("Semantic graph symbol kind cannot be projected to Graphify: {other}"),
        )),
    }
}

fn graphify_file_stem(path: &str) -> String {
    Path::new(path)
        .with_extension("")
        .to_string_lossy()
        .replace('\\', "/")
}

fn graphify_make_id(parts: &[&str]) -> String {
    use unicode_casefold::UnicodeCaseFold;
    use unicode_normalization::UnicodeNormalization;

    let joined = parts
        .iter()
        .filter(|part| !part.is_empty())
        .map(|part| part.trim_matches(|character| character == '_' || character == '.'))
        .collect::<Vec<_>>()
        .join("_");
    let mut normalized = String::new();
    let mut separator_pending = false;
    for character in joined.nfkc() {
        if character.is_alphanumeric() {
            if separator_pending && !normalized.is_empty() {
                normalized.push('_');
            }
            normalized.push(character);
            separator_pending = false;
        } else {
            separator_pending = true;
        }
    }
    normalized.as_str().case_fold().collect()
}

fn validate_graphify_incremental_base(
    base: &Value,
    fragment: &Value,
    scope: &GraphifyManifestScope,
    workspace_root: &Path,
) -> std::result::Result<(), AgentError> {
    let base_nodes = base["nodes"].as_array().ok_or_else(|| {
        agent_error("FULL_REBUILD_REQUIRED", "Base graph does not contain a nodes array.")
    })?;
    let selected_relative = scope
        .selected
        .iter()
        .filter_map(|path| path.strip_prefix(workspace_root).ok())
        .map(|path| path.to_string_lossy().replace('\\', "/"))
        .collect::<BTreeSet<_>>();
    let mut base_ids = BTreeSet::new();
    let mut base_keys = BTreeSet::new();
    for node in base_nodes {
        let source_file = node["source_file"].as_str().unwrap_or_default();
        if graphify_is_kotlin_path(Path::new(source_file)) {
            let id = node["id"].as_str().unwrap_or_default();
            if node["metadata"]["id_schema_version"] != "kast:graphify:v2" {
                return Err(agent_error(
                    "FULL_REBUILD_REQUIRED",
                    "Base graph mixes Kotlin identity schemas; run one full Kast rebuild.",
                ));
            }
            base_ids.insert(id.to_string());
            for range in node["metadata"]["exact_ranges"]
                .as_array()
                .into_iter()
                .flatten()
            {
                let range_source = range["source_file"].as_str().ok_or_else(|| {
                    agent_error(
                        "FULL_REBUILD_REQUIRED",
                        "A Kotlin base identity has no source file.",
                    )
                })?;
                if selected_relative.contains(range_source) {
                    let key = range["canonical_key"].as_str().ok_or_else(|| {
                        agent_error(
                            "FULL_REBUILD_REQUIRED",
                            "A selected Kotlin base identity has no Kast canonical key.",
                        )
                    })?;
                    base_keys.insert(key.to_string());
                }
            }
        }
    }
    let new_keys = fragment["nodes"]
        .as_array()
        .into_iter()
        .flatten()
        .flat_map(|node| {
            node["metadata"]["exact_ranges"]
                .as_array()
                .into_iter()
                .flatten()
        })
        .filter(|range| {
            range["source_file"]
                .as_str()
                .is_some_and(|source_file| selected_relative.contains(source_file))
        })
        .filter_map(|range| range["canonical_key"].as_str())
        .map(str::to_string)
        .collect::<BTreeSet<_>>();
    if base_keys != new_keys {
        return Err(agent_error(
            "FULL_REBUILD_REQUIRED",
            "Kotlin symbol identities changed (addition, deletion, rename, or signature change).",
        ));
    }
    for edge in fragment["edges"].as_array().into_iter().flatten() {
        let target = edge["target"].as_str().unwrap_or_default();
        let target_is_current = fragment["nodes"]
            .as_array()
            .into_iter()
            .flatten()
            .any(|node| node["id"] == target);
        if !target_is_current && !base_ids.contains(target) {
            return Err(agent_error(
                "FULL_REBUILD_REQUIRED",
                "A scoped Kotlin relation targets a node missing from the validated Kast Graphify-v2 base graph.",
            ));
        }
    }
    Ok(())
}

fn write_graphify_fragment_atomically(
    output_file: &Path,
    fragment: &Value,
) -> std::result::Result<(), AgentError> {
    let parent = output_file.parent().ok_or_else(|| {
        agent_error("AGENT_USAGE", "--output-file must have a parent directory.")
    })?;
    std::fs::create_dir_all(parent).map_err(|error| {
        agent_error("GRAPHIFY_WRITE_FAILED", format!("Cannot create output directory: {error}"))
    })?;
    let temporary_path = parent.join(format!(
        ".{}.{}.tmp",
        output_file
            .file_name()
            .and_then(|value| value.to_str())
            .unwrap_or("graphify"),
        uuid::Uuid::new_v4()
    ));
    let mut temporary = std::fs::File::create(&temporary_path).map_err(|error| {
        agent_error("GRAPHIFY_WRITE_FAILED", format!("Cannot create atomic output: {error}"))
    })?;
    serde_json::to_writer_pretty(&mut temporary, fragment).map_err(|error| {
        agent_error("GRAPHIFY_WRITE_FAILED", format!("Cannot encode Graphify output: {error}"))
    })?;
    temporary.sync_all().map_err(|error| {
        agent_error("GRAPHIFY_WRITE_FAILED", format!("Cannot sync Graphify output: {error}"))
    })?;
    std::fs::rename(&temporary_path, output_file).map_err(|error| {
        let _ = std::fs::remove_file(&temporary_path);
        agent_error("GRAPHIFY_WRITE_FAILED", format!("Cannot publish Graphify output: {error}"))
    })?;
    Ok(())
}

#[cfg(test)]
mod graphify_projection_tests {
    use super::*;
    use clap::Parser;

    fn parsed_graphify_batch_size(extra: &[&str]) -> u16 {
        let mut arguments = vec![
            "kast",
            "agent",
            "graphify",
            "--workspace-root",
            "/workspace",
            "--manifest",
            "/workspace/manifest.json",
            "--output-file",
            "/workspace/fragment.json",
        ];
        arguments.extend_from_slice(extra);
        let cli = crate::cli::Cli::try_parse_from(arguments).expect("Graphify arguments");
        let Some(crate::cli::Command::Agent(crate::cli::AgentArgs {
            command: Some(AgentCommand::Graphify(args)),
        })) = cli.command
        else {
            panic!("expected Graphify command");
        };
        args.batch_size
    }

    #[test]
    fn graphify_batch_size_is_bounded_and_defaults_to_twenty_five() {
        assert_eq!(parsed_graphify_batch_size(&[]), 25);
        assert_eq!(parsed_graphify_batch_size(&["--batch-size", "500"]), 500);
        assert!(crate::cli::Cli::try_parse_from([
            "kast",
            "agent",
            "graphify",
            "--workspace-root",
            "/workspace",
            "--manifest",
            "/workspace/manifest.json",
            "--output-file",
            "/workspace/fragment.json",
            "--batch-size",
            "501",
        ])
        .is_err());
    }

    #[test]
    fn graphify_projection_uses_native_ids_and_aggregates_overloads_and_boundaries() {
        let semantic = json!({
            "symbols": [
                {"canonicalKey":"file:src/demo/Sample.kt","kind":"FILE","name":"Sample.kt","path":"src/demo/Sample.kt","startOffset":0,"endOffset":100,"line":1},
                {"canonicalKey":"class:CLASS:src/demo/Sample.kt:10:demo.Box","kind":"CLASS","name":"Box","fqName":"demo.Box","path":"src/demo/Sample.kt","startOffset":10,"endOffset":90,"line":3},
                {"canonicalKey":"callable:src/demo/Sample.kt:20:demo.Box.pick|string","kind":"MEMBER_FUNCTION","name":"pick","fqName":"demo.Box.pick","signature":"demo.Box.pick|string","ownerKey":"class:CLASS:src/demo/Sample.kt:10:demo.Box","path":"src/demo/Sample.kt","startOffset":20,"endOffset":40,"line":4},
                {"canonicalKey":"callable:src/demo/Sample.kt:50:demo.Box.pick|int","kind":"MEMBER_FUNCTION","name":"pick","fqName":"demo.Box.pick","signature":"demo.Box.pick|int","ownerKey":"class:CLASS:src/demo/Sample.kt:10:demo.Box","path":"src/demo/Sample.kt","startOffset":50,"endOffset":70,"line":5}
            ],
            "boundarySymbols": [
                {"canonicalKey":"class:CLASS:src/shared/Target.kt:0:demo.Target","kind":"CLASS","name":"Target","fqName":"demo.Target","path":"src/shared/Target.kt","startOffset":0,"endOffset":20,"line":1}
            ],
            "relations": [
                {"sourceKey":"callable:src/demo/Sample.kt:20:demo.Box.pick|string","targetKey":"class:CLASS:src/shared/Target.kt:0:demo.Target","kind":"REFERENCES","context":"RETURN_TYPE","sourcePath":"src/demo/Sample.kt","startOffset":20,"endOffset":40,"line":4}
            ]
        });

        let fragment = project_graphify_fragment(&semantic).expect("projection");
        let ids = fragment["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(|node| node["id"].as_str())
            .collect::<BTreeSet<_>>();

        assert!(ids.contains("src_demo_sample"));
        assert!(ids.contains("src_demo_sample_box"));
        assert!(ids.contains("src_demo_sample_box_pick"));
        assert!(ids.contains("src_shared_target_target"));
        let overload = fragment["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .find(|node| node["id"] == "src_demo_sample_box_pick")
            .unwrap();
        assert_eq!(overload["metadata"]["canonical_keys"].as_array().unwrap().len(), 2);
        let boundary = fragment["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .find(|node| node["id"] == "src_shared_target_target")
            .unwrap();
        assert_eq!(boundary["metadata"]["boundary"], "unexpanded");
        assert!(fragment["edges"].as_array().unwrap().iter().all(|edge| {
            ids.contains(edge["source"].as_str().unwrap()) && ids.contains(edge["target"].as_str().unwrap())
        }));
    }

    #[test]
    fn graphify_projection_uses_sorted_simple_graph_winner_and_retains_collapsed_relations() {
        let semantic = json!({
            "symbols": [
                {"canonicalKey":"file:A.kt","kind":"FILE","name":"A.kt","path":"A.kt","startOffset":0,"endOffset":10,"line":1},
                {"canonicalKey":"class:CLASS:demo.A","kind":"CLASS","name":"A","path":"A.kt","startOffset":0,"endOffset":10,"line":1}
            ],
            "boundarySymbols": [],
            "relations": [
                {"sourceKey":"file:A.kt","targetKey":"class:CLASS:demo.A","resolvedTargetKey":"constructor:demo.A.<init>|-||kotlin.String|0","kind":"CONTAINS","context":"NONE","sourcePath":"A.kt","startOffset":0,"endOffset":1,"line":1},
                {"sourceKey":"file:A.kt","targetKey":"class:CLASS:demo.A","kind":"REFERENCES","context":"FIELD","sourcePath":"A.kt","startOffset":2,"endOffset":3,"line":2}
            ]
        });
        let fragment = project_graphify_fragment(&semantic).expect("projection");
        assert_eq!(fragment["edges"][0]["relation"], "references");
        assert_eq!(fragment["edges"][0]["metadata"]["collapsed_relations"].as_array().unwrap().len(), 2);
        assert_eq!(
            fragment["edges"][0]["metadata"]["collapsed_relations"][0]
                ["resolved_target_key"],
            "constructor:demo.A.<init>|-||kotlin.String|0"
        );
        assert_eq!(fragment["edges"][0]["_origin"], "ast");
    }

    #[test]
    fn graphify_manifest_admits_only_absolute_contained_kotlin_paths() {
        let workspace = tempfile::tempdir().expect("workspace");
        let kotlin = workspace.path().join("Sample.kt");
        let kotlin_script = workspace.path().join("build.gradle.kts");
        let rust = workspace.path().join("sample.rs");
        std::fs::write(&kotlin, "class Sample").expect("Kotlin fixture");
        std::fs::write(&kotlin_script, "plugins {}").expect("Kotlin script fixture");
        std::fs::write(&rust, "struct Sample;").expect("Rust fixture");
        let manifest = json!({"files": {"code": [kotlin, kotlin_script, rust]}});

        let canonical_workspace = workspace.path().canonicalize().expect("canonical workspace");
        let scope =
            graphify_manifest_scope(&manifest, &canonical_workspace).expect("manifest scope");

        assert_eq!(scope.selected.len(), 1);
        assert_eq!(scope.selected[0].file_name().unwrap(), "Sample.kt");
        assert!(!scope.incremental);
    }

    #[test]
    fn graphify_deletion_only_manifest_is_incremental() {
        let workspace = tempfile::tempdir().expect("workspace");
        let canonical_workspace = workspace.path().canonicalize().expect("canonical workspace");
        let deleted = canonical_workspace.join("Deleted.kt");
        let manifest = json!({"deleted_files": [deleted]});
        let scope =
            graphify_manifest_scope(&manifest, &canonical_workspace).expect("manifest scope");

        assert!(scope.incremental);
        assert_eq!(scope.removed.len(), 1);
        assert_eq!(scope.removed[0].file_name().unwrap(), "Deleted.kt");
    }

    #[test]
    fn graphify_page_size_respects_smaller_backend_limit() {
        let capabilities = json!({"limits": {"maxResults": 17}});

        assert_eq!(graphify_page_size_from_capabilities(&capabilities).unwrap(), 17);
    }

    #[test]
    fn graphify_id_normalization_matches_unicode_native_scheme() {
        assert_eq!(
            graphify_make_id(&["src/Ｆoo", "Straße.pick"]),
            "src_foo_strasse_pick",
        );
    }

    #[test]
    fn graphify_file_snapshot_rejects_a_changed_source() {
        let workspace = tempfile::tempdir().expect("workspace");
        let source = workspace.path().join("Sample.kt");
        std::fs::write(&source, "class Before").expect("source fixture");
        let expected = graphify_capture_file_hashes(
            std::slice::from_ref(&source),
            workspace.path(),
        )
        .expect("initial snapshot");
        std::fs::write(&source, "class After").expect("changed source fixture");

        let error = graphify_verify_current_file_hashes(
            std::slice::from_ref(&source),
            workspace.path(),
            &expected,
        )
        .expect_err("changed input must abort extraction");

        assert_eq!(error.code, "WORKSPACE_CHANGED");
    }

    #[test]
    fn graphify_incremental_rejects_mixed_kotlin_identity_schemas() {
        let workspace = tempfile::tempdir().expect("workspace");
        let selected = workspace.path().join("Sample.kt");
        std::fs::write(&selected, "class Sample").expect("Kotlin fixture");
        let scope = GraphifyManifestScope {
            selected: vec![selected],
            removed: Vec::new(),
            incremental: true,
        };
        let base = json!({"nodes": [{
            "id": "tree-sitter-id",
            "source_file": "Sample.kt",
            "metadata": {"canonical_key": "class:CLASS:demo.Sample"}
        }]});
        let fragment = json!({"nodes": [], "edges": []});

        let error = validate_graphify_incremental_base(&base, &fragment, &scope, workspace.path())
            .expect_err("mixed identity must require a full rebuild");

        assert_eq!(error.code, "FULL_REBUILD_REQUIRED");
    }

    #[test]
    fn graphify_output_is_published_atomically_as_valid_json() {
        let directory = tempfile::tempdir().expect("output directory");
        let output = directory.path().join("fragment.json");
        let fragment = json!({
            "nodes": [], "edges": [], "hyperedges": [], "input_tokens": 0, "output_tokens": 0
        });

        write_graphify_fragment_atomically(&output, &fragment).expect("atomic output");

        assert_eq!(read_graphify_json(&output, "output").unwrap(), fragment);
        assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(), 1);
    }
}
