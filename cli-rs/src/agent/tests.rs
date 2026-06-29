#[cfg(test)]
mod tests {
    use super::*;
    use crate::cli::{AgentRawCallDirection, AgentRawResolveArgs, AgentSymbolKind, BackendName};

    #[test]
    fn params_object_becomes_json_rpc_request() {
        let request =
            normalize_input("symbol/resolve", Some(json!({"symbol": "Widget"}))).expect("request");
        assert_eq!(request["method"], "symbol/resolve");
        assert_eq!(request["params"]["symbol"], "Widget");
        assert_eq!(request["id"], 1);
    }

    #[test]
    fn full_json_rpc_request_is_preserved() {
        let input = json!({
            "jsonrpc": "2.0",
            "method": "symbol/resolve",
            "params": { "symbol": "Widget" },
            "id": 42
        });
        let request = normalize_input("symbol/resolve", Some(input.clone())).expect("request");
        assert_eq!(request, input);
    }

    #[test]
    fn prior_agent_envelope_request_is_pipe_compatible() {
        let input = json!({
            "ok": true,
            "method": "symbol/resolve",
            "request": {
                "jsonrpc": "2.0",
                "method": "symbol/resolve",
                "params": { "symbol": "Widget" },
                "id": 1
            }
        });
        let request = normalize_input("symbol/resolve", Some(input)).expect("request");
        assert_eq!(request["params"]["symbol"], "Widget");
    }

    #[test]
    fn next_request_object_can_feed_the_selected_method() {
        let input = json!({
            "nextRequest": {
                "symbol": "Widget",
                "kind": "class"
            }
        });
        let request = normalize_input("symbol/resolve", Some(input)).expect("request");
        assert_eq!(request["method"], "symbol/resolve");
        assert_eq!(request["params"]["kind"], "class");
    }

    #[test]
    fn method_mismatch_is_rejected() {
        let input = json!({
            "jsonrpc": "2.0",
            "method": "symbol/references",
            "params": { "symbol": "Widget" },
            "id": 1
        });
        let error = normalize_input("symbol/resolve", Some(input)).expect_err("mismatch");
        assert_eq!(error.code, "AGENT_METHOD_MISMATCH");
    }

    #[test]
    fn raw_resolve_alias_builds_nested_position_params() {
        let args = AgentRawResolveArgs {
            position: AgentPositionArgs {
                runtime: AgentRuntimeArgs {
                    workspace_root: Some("/repo".into()),
                    backend_name: Some(BackendName::Idea),
                },
                file_path: "src/main.kt".to_string(),
                offset: 12,
            },
            include_declaration_scope: true,
            include_documentation: false,
        };
        let alias = raw_resolve_alias(args);
        assert_eq!(alias.method, "raw/resolve");
        assert_eq!(alias.params["position"]["filePath"], "src/main.kt");
        assert_eq!(alias.params["position"]["offset"], 12);
        assert_eq!(alias.params["includeDeclarationScope"], true);
        assert_eq!(alias.runtime.backend_name, Some(BackendName::Idea));
    }

    #[test]
    fn symbol_resolve_alias_uses_catalog_kind_values() {
        let args = AgentSymbolResolveArgs {
            runtime: AgentRuntimeArgs::default(),
            symbol: "Widget".to_string(),
            file_hint: None,
            kind: Some(AgentSymbolKind::Class),
            containing_type: None,
            include_declaration_scope: false,
            include_documentation: true,
            surrounding_lines: Some(3),
            include_surrounding_members: false,
        };
        let alias = symbol_resolve_alias(args);
        assert_eq!(alias.params["kind"], "class");
        assert_eq!(alias.params["includeDocumentation"], true);
        assert_eq!(alias.params["surroundingLines"], 3);
    }

    #[test]
    fn raw_call_hierarchy_alias_uses_backend_direction_values() {
        let args = AgentRawCallHierarchyArgs {
            position: AgentPositionArgs {
                runtime: AgentRuntimeArgs::default(),
                file_path: "src/main.kt".to_string(),
                offset: 12,
            },
            direction: AgentRawCallDirection::Incoming,
            depth: Some(2),
            max_total_calls: None,
            max_children_per_node: None,
            timeout_millis: None,
        };
        let alias = raw_call_hierarchy_alias(args);
        assert_eq!(alias.params["direction"], "INCOMING");
        assert_eq!(alias.params["depth"], 2);
    }
}
