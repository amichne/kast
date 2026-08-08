#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentPublicCapabilityProjection {
    capability: crate::agent::public_protocol::Capability,
    operation: crate::agent::public_protocol::OperationId,
    command: String,
}

fn public_read_capabilities(raw_read_capabilities: &[String]) -> Vec<AgentPublicCapabilityProjection> {
    crate::agent::public_protocol::operation_definitions()
        .filter(|definition| {
            definition
                .capability
                .backend_capability()
                .is_some_and(|backend| raw_read_capabilities.iter().any(|raw| raw == backend))
                && public_capability_route_is_callable(*definition)
        })
        .map(|definition| AgentPublicCapabilityProjection {
            capability: definition.capability,
            operation: definition.id,
            command: format!("kast {}", definition.cli.segments.join(" ")),
        })
        .collect()
}

fn public_capability_route_is_callable(
    definition: crate::agent::public_protocol::OperationDefinition,
) -> bool {
    let mut command = crate::cli::KastCli::command();
    for segment in definition.cli.segments {
        let Some(next) = command
            .get_subcommands()
            .find(|subcommand| subcommand.get_name() == *segment)
            .cloned()
        else {
            return false;
        };
        command = next;
    }
    true
}

#[cfg(test)]
mod public_capability_route_tests {
    use super::*;

    #[test]
    fn every_backend_projected_capability_resolves_through_the_public_clap_tree() {
        let definitions = crate::agent::public_protocol::operation_definitions()
            .filter(|definition| definition.capability.backend_capability().is_some())
            .collect::<Vec<_>>();
        assert_eq!(definitions.len(), 1);
        assert_eq!(
            definitions[0].id,
            crate::agent::public_protocol::OperationId::FileList
        );
        assert!(
            definitions
                .into_iter()
                .all(public_capability_route_is_callable)
        );
    }

    #[test]
    fn public_read_evidence_requires_backend_support_for_the_registered_operation() {
        assert!(public_read_capabilities(&[]).is_empty());
        assert_eq!(
            public_read_capabilities(&["WORKSPACE_FILES".to_string()]),
            vec![AgentPublicCapabilityProjection {
                capability: crate::agent::public_protocol::Capability::WorkspaceFiles,
                operation: crate::agent::public_protocol::OperationId::FileList,
                command: "kast file list".to_string(),
            }]
        );
    }
}
