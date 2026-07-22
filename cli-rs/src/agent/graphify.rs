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
                "Incremental Kotlin extraction requires --base-graph with Kast-v1 identities.",
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
        json!({"symbols": [], "relations": [], "coverage": {"files": [], "omittedExternalTargetCount": 0}})
    } else {
        match graphify_semantic_pages(&args.runtime, &scope) {
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
    let mut symbols = Vec::new();
    let mut relations = Vec::new();
    let coverage: Value;
    let mut continuation: Option<String> = None;
    loop {
        let params = drop_nulls(json!({
            "filePaths": scope.selected,
            "removedFilePaths": scope.removed,
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
            return Err(Box::new(envelope));
        }
        let result = envelope.result.as_ref().expect("successful RPC has a result");
        symbols.extend(result["symbols"].as_array().cloned().unwrap_or_default());
        relations.extend(result["relations"].as_array().cloned().unwrap_or_default());
        let next = result["nextPageToken"].as_str().map(str::to_string);
        if next.is_none() {
            coverage = result["coverage"].clone();
            break;
        }
        continuation = next;
    }
    Ok(json!({"symbols": symbols, "relations": relations, "coverage": coverage}))
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

fn project_graphify_fragment(semantic: &Value) -> std::result::Result<Value, AgentError> {
    let symbols = semantic["symbols"].as_array().ok_or_else(|| {
        agent_error("SEMANTIC_GRAPH_INVALID", "Semantic graph response omitted symbols.")
    })?;
    let mut ids = BTreeMap::new();
    let mut nodes = Vec::new();
    for symbol in symbols {
        if symbol["kind"] == "CONSTRUCTOR" {
            continue;
        }
        let canonical_key = graphify_required_str(symbol, "canonicalKey")?;
        let id = graphify_public_id(canonical_key);
        ids.insert(canonical_key.to_string(), id.clone());
        nodes.push(json!({
            "id": id,
            "label": graphify_required_str(symbol, "name")?,
            "file_type": "code",
            "source_file": graphify_required_str(symbol, "path")?,
            "source_location": format!("L{}", symbol["line"].as_u64().unwrap_or(1)),
            "_origin": "ast",
            "metadata": {
                "canonical_key": canonical_key,
                "fq_name": symbol.get("fqName").cloned().unwrap_or(Value::Null),
                "signature": symbol.get("signature").cloned().unwrap_or(Value::Null),
                "kotlin_kind": graphify_required_str(symbol, "kind")?,
                "provider": "kast-k2",
                "id_schema_version": "kast:kotlin:v1",
                "exact_range": {
                    "start_offset": symbol["startOffset"],
                    "end_offset": symbol["endOffset"],
                    "line": symbol["line"],
                }
            }
        }));
    }
    nodes.sort_by(|left, right| left["id"].as_str().cmp(&right["id"].as_str()));

    let relations = semantic["relations"].as_array().ok_or_else(|| {
        agent_error("SEMANTIC_GRAPH_INVALID", "Semantic graph response omitted relations.")
    })?;
    let mut occurrences: BTreeMap<GraphifyOccurrenceKey, GraphifyRelationVariant> = BTreeMap::new();
    for relation in relations {
        let source_key = graphify_required_str(relation, "sourceKey")?;
        let target_key = graphify_required_str(relation, "targetKey")?;
        let Some(source) = ids.get(source_key).cloned() else {
            continue;
        };
        let target = ids
            .get(target_key)
            .cloned()
            .unwrap_or_else(|| graphify_public_id(target_key));
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

fn graphify_public_id(canonical_key: &str) -> String {
    use sha2::{Digest, Sha256};
    format!(
        "kast:kotlin:v1:{}",
        hex::encode(Sha256::digest(canonical_key.as_bytes()))
    )
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
            if !id.starts_with("kast:kotlin:v1:") {
                return Err(agent_error(
                    "FULL_REBUILD_REQUIRED",
                    "Base graph mixes Kotlin identity schemas; run one full Kast rebuild.",
                ));
            }
            base_ids.insert(id.to_string());
            if selected_relative.contains(source_file) {
                let key = node["metadata"]["canonical_key"].as_str().ok_or_else(|| {
                    agent_error(
                        "FULL_REBUILD_REQUIRED",
                        "A selected Kotlin base node has no Kast canonical key.",
                    )
                })?;
                base_keys.insert(key.to_string());
            }
        }
    }
    let new_keys = fragment["nodes"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|node| node["metadata"]["canonical_key"].as_str())
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
                "A scoped Kotlin relation targets a node missing from the validated Kast-v1 base graph.",
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

    #[test]
    fn graphify_ids_are_deterministic_and_namespaced() {
        assert_eq!(graphify_public_id("class:CLASS:demo.Box"), graphify_public_id("class:CLASS:demo.Box"));
        assert!(graphify_public_id("class:CLASS:demo.Box").starts_with("kast:kotlin:v1:"));
    }

    #[test]
    fn graphify_projection_uses_sorted_simple_graph_winner_and_retains_collapsed_relations() {
        let semantic = json!({
            "symbols": [
                {"canonicalKey":"file:A.kt","kind":"FILE","name":"A.kt","path":"A.kt","startOffset":0,"endOffset":10,"line":1},
                {"canonicalKey":"class:CLASS:demo.A","kind":"CLASS","name":"A","path":"A.kt","startOffset":0,"endOffset":10,"line":1}
            ],
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
