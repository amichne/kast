#[cfg(test)]
mod tests {
    use super::*;
    use crate::cli::{AgentRawCallDirection, AgentRawResolveArgs, AgentSymbolKind, BackendName};

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
