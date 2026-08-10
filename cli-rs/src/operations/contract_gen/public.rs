fn public_generated_files(manifest_dir: &Path) -> Result<BTreeMap<PathBuf, String>> {
    use crate::agent::public_protocol::operation_definitions;

    let repository_root = manifest_dir.parent().ok_or_else(|| {
        CliError::new(
            "PUBLIC_CONTRACT_PATH_INVALID",
            "The CLI manifest has no repository parent.",
        )
    })?;
    let source = manifest_dir.join("protocol/source");
    let definitions = operation_definitions().collect::<Vec<_>>();
    let mut files = BTreeMap::new();
    files.insert(
        source.join("public-operations.json"),
        json_file_content(&serde_json::json!({
            "schemaVersion": 1,
            "operations": definitions,
        }))?,
    );
    files.insert(
        source.join("public-request.schema.json"),
        json_file_content(&public_request_schema(&definitions))?,
    );
    files.insert(
        source.join("public-result.schema.json"),
        json_file_content(&public_result_schema(&definitions))?,
    );
    files.insert(
        source.join("public-rpc-mappings.json"),
        json_file_content(&public_rpc_mappings(&definitions))?,
    );
    files.insert(
        source.join("public-capabilities.json"),
        json_file_content(&public_capabilities(&definitions))?,
    );
    files.insert(
        repository_root.join("docs/public/reference/cli.md"),
        render_cli_reference(&definitions),
    );
    files.insert(
        manifest_dir.join("resources/kast/SKILL.md"),
        render_public_skill(&definitions),
    );
    files.insert(
        source.join("public-runbook.md"),
        render_public_runbook(&definitions),
    );
    files.insert(
        manifest_dir.join("protocol/completions/_kast"),
        render_zsh_completion(&definitions),
    );
    files.insert(
        manifest_dir.join("protocol/golden/public-workflow.json"),
        json_file_content(&golden_workflow())?,
    );
    files.insert(
        manifest_dir.join("protocol/benchmarks/public-protocol-v1.json"),
        json_file_content(&benchmark_fixture())?,
    );
    Ok(files)
}

fn public_request_schema(
    definitions: &[crate::agent::public_protocol::OperationDefinition],
) -> Value {
    let mut request_types = BTreeMap::new();
    let mut variants = Vec::new();
    for definition in definitions {
        let type_name = serialized_name(definition.request_type);
        request_types
            .entry(type_name.clone())
            .or_insert_with(|| request_type_schema(definition.request_type));
        variants.push(serde_json::json!({
            "type": "object",
            "additionalProperties": false,
            "required": ["operation", "request"],
            "properties": {
                "operation": {"const": definition.id},
                "request": {"$ref": format!("#/$defs/{type_name}")},
            },
        }));
    }
    serde_json::json!({
        "schemaVersion": 1,
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://kast.michne.com/protocol/public-request.schema.json",
        "title": "Kast public typed request",
        "oneOf": variants,
        "$defs": request_types,
    })
}

fn request_type_schema(request: crate::agent::public_protocol::RequestType) -> Value {
    use crate::agent::public_protocol::RequestType::*;
    let string = || serde_json::json!({"type": "string", "minLength": 1});
    let selector = || serde_json::json!({"type": "string", "format": "kast-symbol-selector"});
    let continuation = || serde_json::json!({"type": "string", "format": "kast-continuation"});
    let path = || serde_json::json!({"type": "string", "format": "workspace-kotlin-path"});
    let fields: Vec<(&str, Value, bool)> = match request {
        WorkspaceHome | WorkspaceUp => Vec::new(),
        WorkspaceRefresh => vec![("files", serde_json::json!({"type": "array", "items": path()}), false)],
        WorkspaceExternalize => vec![("failureIds", serde_json::json!({"type": "array", "minItems": 1, "items": {"type": "string", "format": "kast-external-failure-id"}}), true)],
        FileList => vec![("match", string(), false), ("continuation", continuation(), false)],
        SymbolSearch | SymbolResolve => vec![("query", string(), true)],
        SymbolShow => vec![("selector", selector(), true)],
        ExactRelation | GraphImpact => vec![("selector", selector(), true), ("continuation", continuation(), false)],
        GraphProjection => vec![("scope", serde_json::json!({"enum": ["symbol", "package", "module"]}), false)],
        GraphNodes => vec![("continuation", continuation(), false)],
        GraphNeighbors => vec![("nodeSelector", serde_json::json!({"type": "string", "format": "kast-graph-node-selector"}), true)],
        GraphDerive => vec![("out", path(), true), ("prior", path(), false), ("experimentalDerivedTopology", serde_json::json!({"const": true}), true)],
        DiagnosticCheck => vec![("files", serde_json::json!({"type": "array", "items": path()}), false)],
        ChangePlanRename => vec![("selector", selector(), true), ("name", string(), true)],
        ChangePlanAddFile | ChangePlanAddDeclaration => vec![("file", path(), true), ("content", string(), true)],
        ChangePlanReplace => vec![("selector", selector(), true), ("content", string(), true)],
        ChangeApply => vec![("planId", serde_json::json!({"type": "string", "format": "kast-plan-id"}), true)],
        ChangeRecover => vec![("recoveryId", serde_json::json!({"type": "string", "format": "kast-recovery-id"}), true)],
    };
    object_schema(fields)
}

fn object_schema(fields: Vec<(&str, Value, bool)>) -> Value {
    let mut properties = Map::new();
    let mut required = Vec::new();
    for (name, schema, is_required) in fields {
        properties.insert(name.to_string(), schema);
        if is_required {
            required.push(Value::String(name.to_string()));
        }
    }
    serde_json::json!({
        "type": "object",
        "additionalProperties": false,
        "properties": properties,
        "required": required,
    })
}

fn public_result_schema(
    definitions: &[crate::agent::public_protocol::OperationDefinition],
) -> Value {
    let failure_types = [
        "actionable-failure", "backend-contract-violation", "backend-rejected", "continuation-invalid",
        "continuation-mismatch", "continuation-stale", "invalid-input", "selector-rejected",
        "mutation-non-success", "subject-identity-mismatch", "subject-not-found",
        "unsupported-subject-kind",
    ];
    let mut result_definitions = BTreeMap::new();
    for definition in definitions {
        for result_type in definition.result_discriminators {
            result_definitions.entry(format!("result-{result_type}")).or_insert_with(|| {
                serde_json::json!({
                    "type": "object",
                    "required": ["type"],
                    "properties": {"type": {"const": result_type}},
                })
            });
        }
    }
    result_definitions.insert("rejected".to_string(), serde_json::json!({
        "type": "object",
        "additionalProperties": false,
        "required": ["type", "failure"],
        "properties": {
            "type": {"const": "rejected"},
            "failure": {
                "type": "object",
                "required": ["type"],
                "properties": {"type": {"enum": failure_types}},
            },
        },
    }));
    result_definitions.insert("envelope".to_string(), serde_json::json!({
        "type": "object",
        "additionalProperties": false,
        "required": ["schemaVersion", "operation", "status", "result"],
        "properties": {
            "schemaVersion": {"const": crate::agent::public_protocol::PUBLIC_PROTOCOL_SCHEMA_VERSION},
            "operation": {"enum": definitions.iter().map(|definition| definition.id).collect::<Vec<_>>()},
            "status": {"enum": ["complete", "qualified", "rejected"]},
            "result": {"type": "object"},
        },
        "allOf": [{
            "if": {"properties": {"status": {"const": "rejected"}}, "required": ["status"]},
            "then": {"properties": {"result": {"$ref": "#/$defs/rejected"}}},
            "else": {"properties": {"result": {"not": {"$ref": "#/$defs/rejected"}}}},
        }],
    }));
    let variants = definitions.iter().map(|definition| {
        let mut results = definition.result_discriminators.iter().map(|result_type| {
            serde_json::json!({"$ref": format!("#/$defs/result-{result_type}")})
        }).collect::<Vec<_>>();
        results.push(serde_json::json!({"$ref": "#/$defs/rejected"}));
        serde_json::json!({"allOf": [
            {"$ref": "#/$defs/envelope"},
            {"properties": {
                "operation": {"const": definition.id},
                "result": {"oneOf": results},
            }},
        ]})
    }).collect::<Vec<_>>();
    serde_json::json!({
        "schemaVersion": 1,
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://kast.michne.com/protocol/public-result.schema.json",
        "title": "Kast public canonical result envelope",
        "oneOf": variants,
        "$defs": result_definitions,
    })
}

fn public_rpc_mappings(
    definitions: &[crate::agent::public_protocol::OperationDefinition],
) -> Value {
    let operations = definitions
        .iter()
        .map(|definition| serde_json::json!({
            "operation": definition.id,
            "requestType": definition.request_type,
            "methods": definition.rpc_methods,
            "bindings": definition.rpc_bindings,
        }))
        .collect::<Vec<_>>();
    serde_json::json!({"schemaVersion": 1, "operations": operations})
}

fn public_capabilities(
    definitions: &[crate::agent::public_protocol::OperationDefinition],
) -> Value {
    let mut grouped: BTreeMap<String, Vec<Value>> = BTreeMap::new();
    for definition in definitions {
        grouped
            .entry(serialized_name(definition.capability))
            .or_default()
            .push(serde_json::json!({
                "operation": definition.id,
                "route": definition.cli.syntax,
                "evidence": definition.evidence,
            }));
    }
    let capabilities = grouped
        .into_iter()
        .map(|(capability, operations)| serde_json::json!({
            "capability": capability,
            "operations": operations,
        }))
        .collect::<Vec<_>>();
    serde_json::json!({"schemaVersion": 1, "capabilities": capabilities})
}

fn render_cli_reference(
    definitions: &[crate::agent::public_protocol::OperationDefinition],
) -> String {
    let mut text = "---\ntype: Generated Reference\ntitle: CLI Contract\ndescription: Generated facts for the executable public Kast CLI.\ntags: [generated, cli, reference, commands]\ncode_sources:\n  - path: cli-rs/src/agent/public_protocol/registry.rs\n---\n\n# CLI Contract\n\n> Generated from the typed public operation registry.\n\nThis page lists the executable `kast` commands and the contract each command preserves. Change the typed public registry, then regenerate this page.\n\nEvery command supports `--output toon|json`. Both formats preserve the same canonical protocol envelope. Compact TOON retains every semantic discriminator. Uppercase shell variables represent values returned by Kast.\n\nEvery result contains `schemaVersion`, `operation`, `status`, and `result.type`. A `qualified` result names its limitations. A `rejected` result contains a closed typed failure.\n\n## Operations\n\n| Operation | CLI syntax | Request type | Result type | Paging |\n| --- | --- | --- | --- | --- |\n".to_string();
    for definition in definitions {
        let paging = match definition.paging {
            crate::agent::public_protocol::Paging::Unpaged => "unpaged".to_string(),
            crate::agent::public_protocol::Paging::Continuation { continuation_type } => {
                format!("continuation ({})", serialized_name(continuation_type))
            }
        };
        text.push_str(&format!(
            "| `{}` | `{}` | `{}` | `{}` | {} |\n",
            definition.id.as_str(), shell_variable_syntax(definition.cli.syntax),
            serialized_name(definition.request_type), serialized_name(definition.result_type), paging,
        ));
    }
    text.push_str("\nDiagnostics do not block reference indexing.\n");
    text.push_str("\n## Composition\n\nUse `query` only for `symbol.search` and `symbol.resolve`. Copy each Kast-issued `selector` verbatim into a compatible exact operation. Repeat a paged operation with its own opaque `continuation`; continuations never cross operations. Apply only a returned plan ID with `kast change apply --plan-id $PLAN_ID`. Recover only a returned recovery ID with `kast change recover --recovery-id $RECOVERY_ID`.\n\nPublic paths are workspace-relative and use forward slashes. A qualified name, location, path, offset, or graph node selector is never a symbol selector.\n\n## Boundary semantics\n\nExternalizing an eligible content-bound failure records an explicit `UNKNOWN` graph boundary. Unknown, stale, incomplete, and wrong-workspace evidence fails closed.\n");
    text
}

fn shell_variable_syntax(syntax: &str) -> String {
    syntax.replace('<', "$").replace('>', "")
}

fn render_public_skill(
    definitions: &[crate::agent::public_protocol::OperationDefinition],
) -> String {
    let mut text = "---\nname: kast\ndescription: Use when Kotlin or Gradle work needs compiler-backed discovery, exact traversal, graph evidence, diagnostics, or validated changes through Kast.\n---\n\n# Kast\n\n<!-- Generated from the typed public operation registry. -->\n\nUse `kast` from the target workspace. Read the canonical envelope; never infer identity from display data.\n\n## Route by semantic state\n\n- Unknown target -> `symbol.search`.\n- Exact textual target -> `symbol.resolve`.\n- Have selector -> a compatible exact operation.\n- Have continuation -> repeat the same operation.\n- Have plan ID -> `change.apply`.\n- Have recovery ID -> `change.recover`.\n\nCopy Kast-issued selectors, continuations, plan IDs, and recovery IDs verbatim. Never reconstruct them from qualified name, location, path, offset, kind, or container.\n\n## Registered syntax\n\n| Operation | Command | Valid successors |\n| --- | --- | --- |\n".to_string();
    for definition in definitions {
        let successors = definition
            .successors
            .iter()
            .map(|operation| operation.as_str())
            .collect::<Vec<_>>()
            .join(", ");
        text.push_str(&format!(
            "| `{}` | `{}` | {} |\n",
            definition.id.as_str(), definition.cli.syntax, successors,
        ));
    }
    text.push_str("\nPipe change content on standard input; do not place source text in argv. Use `--output toon` for compact TOON or `--output json` for JSON. Both retain `schemaVersion`, `operation`, `status`, and `result.type`. Treat only complete evidence and a `VERIFIED` mutation receipt as success. A qualified result has explicit limitations; rejected, conflicted, rolled-back, and recovery-required outcomes are non-success.\n\nFor setup, runtime control, configuration, raw RPC, local-state inspection, or release work, invoke `/kast:developer`. Read `developerOperations.cli`; do not assume `kastctl` is on `PATH`.\n");
    text
}

fn render_public_runbook(
    definitions: &[crate::agent::public_protocol::OperationDefinition],
) -> String {
    let syntax = |id| {
        definitions
            .iter()
            .find(|definition| definition.id == id)
            .expect("runbook operation is registered")
            .cli
            .syntax
    };
    use crate::agent::public_protocol::OperationId;
    format!(
        "# Typed public protocol runbook\n\n<!-- Generated from the typed public operation registry. -->\n\n1. Establish evidence with `{}`.\n2. Discover uncertainty with `{}` or resolve exact text with `{}`.\n3. Copy the emitted selector verbatim into `{}` and `{}`.\n4. Repeat a paged operation with its own returned `--continuation`; never move it to another operation.\n5. Create a selector-bound plan with `{}`. Apply only its returned plan ID with `{}`.\n\nThe workflow rejects qualified names, locations, paths, offsets, graph node selectors, stale selectors, wrong-root selectors, and cross-operation continuations before semantic execution or mutation planning.\n",
        syntax(OperationId::FileList), syntax(OperationId::SymbolSearch),
        syntax(OperationId::SymbolResolve), syntax(OperationId::SymbolShow),
        syntax(OperationId::RelationReferences), syntax(OperationId::ChangePlanRename),
        syntax(OperationId::ChangeApply),
    )
}

fn render_zsh_completion(
    definitions: &[crate::agent::public_protocol::OperationDefinition],
) -> String {
    let mut text = "#compdef kast\n# Generated from the typed public operation registry.\n\n_kast_typed_operations() {\n  local -a operations\n  operations=(\n".to_string();
    for definition in definitions.iter().filter(|definition| !definition.cli.segments.is_empty()) {
        text.push_str(&format!("    '{}:{}'\n", definition.cli.segments.join(" "), definition.id.as_str()));
    }
    text.push_str("  )\n  _describe 'typed Kast operation' operations\n}\n\n_kast_typed_operations \"$@\"\n");
    text
}

fn golden_workflow() -> Value {
    use crate::agent::public_protocol::OperationId::*;
    serde_json::json!({
        "schemaVersion": 1,
        "workflowVersion": "typed-selector-v1",
        "acceptance": {"semanticMisselection": 0, "selectorRoundTripPercent": 100},
        "steps": [
            {"operation": SymbolSearch, "request": {"query": "Widget.render"}, "produces": "selector"},
            {"operation": SymbolShow, "selectorFrom": "steps[0].result.matches[0].selector", "transform": "none"},
            {"operation": RelationReferences, "selectorFrom": "steps[0].result.matches[0].selector", "transform": "none"},
            {"operation": ChangePlanRename, "selectorFrom": "steps[0].result.matches[0].selector", "transform": "none"},
            {"operation": ChangeApply, "planIdFrom": "steps[3].result.planId", "transform": "none"}
        ],
        "forbiddenIdentitySources": ["qualifiedName", "location", "path", "offset", "kind", "container"],
        "evidenceTests": [
            "selectors_round_trip_verbatim_across_the_overloaded_vertical_slice",
            "typed_exact_operations::one_issued_selector_round_trips_verbatim_through_every_relation_consumer",
            "typed_mutation_operations::rejected_mutation_targets_never_enter_planning_or_create_plan_artifacts"
        ],
    })
}

fn benchmark_fixture() -> Value {
    serde_json::json!({
        "schemaVersion": 1,
        "benchmarkVersion": "public-protocol-v1",
        "metrics": [
            "firstCommandValidity", "correctiveCliCallsBeforeSuccess", "selectorRoundTripSuccess",
            "wrongSymbolSelection", "outputSchemaInterpretationFailures", "tokensBeforeDecisiveEvidence",
            "mutationAttemptsRejectedBeforePlanning", "staleContinuationHandling", "staleSelectorHandling"
        ],
        "acceptance": {"semanticMisselection": 0, "selectorProducerToConsumerRoundTripPercent": 100},
        "measurements": {
            "before": {
                "source": "protocol/source/public-semantic-contract-inventory.json",
                "resolvedContractFindings": 8
            },
            "after": {
                "repeatWith": ".github/scripts/test-cli-typed-composable-protocol.sh",
                "semanticMisselection": 0,
                "selectorProducerToConsumerRoundTripPercent": 100,
                "evidenceTests": [
                    "selectors_round_trip_verbatim_across_the_overloaded_vertical_slice",
                    "continuations_are_operation_bound_and_stale_closed",
                    "exact_routes_reject_substitutes_through_closed_selector_authentication"
                ]
            }
        },
        "scenarios": [
            {"id": "unknown-target", "inputState": "unknownTarget", "firstOperation": crate::agent::public_protocol::OperationId::SymbolSearch},
            {"id": "exact-text", "inputState": "exactText", "firstOperation": crate::agent::public_protocol::OperationId::SymbolResolve},
            {"id": "issued-selector", "inputState": "selector", "identityTransformations": 0},
            {"id": "wrong-root-selector", "expected": "rejectedBeforeSemanticExecution"},
            {"id": "stale-selector", "expected": "rejectedBeforeSemanticExecution"},
            {"id": "cross-operation-continuation", "expected": "continuationMismatch"},
            {"id": "mutation-from-query", "expected": "rejectedBeforePlanning"}
        ],
    })
}

fn serialized_name(value: impl Serialize) -> String {
    serde_json::to_value(value)
        .expect("registry metadata serializes")
        .as_str()
        .expect("registry metadata serializes as a string")
        .to_string()
}
