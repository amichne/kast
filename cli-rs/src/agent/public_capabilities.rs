#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentPublicCapability {
    WorkspaceFiles,
}

impl AgentPublicCapability {
    fn backend_capability(self) -> &'static str {
        match self {
            Self::WorkspaceFiles => "WORKSPACE_FILES",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct AgentPublicCapabilityRoute {
    capability: AgentPublicCapability,
    command_segments: &'static [&'static str],
    display_command: &'static str,
}

const AGENT_PUBLIC_CAPABILITY_ROUTES: &[AgentPublicCapabilityRoute] =
    &[AgentPublicCapabilityRoute {
        capability: AgentPublicCapability::WorkspaceFiles,
        command_segments: &["agent", "workspace-files"],
        display_command: "kast agent workspace-files",
    }];

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentPublicCapabilityProjection {
    capability: AgentPublicCapability,
    command: &'static str,
}

fn public_read_capabilities(raw_read_capabilities: &[String]) -> Vec<AgentPublicCapabilityProjection> {
    AGENT_PUBLIC_CAPABILITY_ROUTES
        .iter()
        .filter(|route| {
            raw_read_capabilities
                .iter()
                .any(|raw| raw == route.capability.backend_capability())
                && public_capability_route_is_callable(route)
        })
        .map(|route| AgentPublicCapabilityProjection {
            capability: route.capability,
            command: route.display_command,
        })
        .collect()
}

fn public_capability_route_is_callable(route: &AgentPublicCapabilityRoute) -> bool {
    let mut command = crate::cli::Cli::command();
    for segment in route.command_segments {
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
    fn every_registered_public_capability_resolves_through_the_clap_command_tree() {
        assert_eq!(
            AGENT_PUBLIC_CAPABILITY_ROUTES,
            &[AgentPublicCapabilityRoute {
                capability: AgentPublicCapability::WorkspaceFiles,
                command_segments: &["agent", "workspace-files"],
                display_command: "kast agent workspace-files",
            }]
        );
        assert!(
            AGENT_PUBLIC_CAPABILITY_ROUTES
                .iter()
                .all(public_capability_route_is_callable)
        );
    }

    #[test]
    fn public_read_evidence_requires_both_backend_support_and_a_callable_route() {
        assert!(public_read_capabilities(&[]).is_empty());
        assert_eq!(
            public_read_capabilities(&["WORKSPACE_FILES".to_string()]),
            vec![AgentPublicCapabilityProjection {
                capability: AgentPublicCapability::WorkspaceFiles,
                command: "kast agent workspace-files",
            }]
        );
    }
}
