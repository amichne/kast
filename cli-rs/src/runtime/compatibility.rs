#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd)]
#[serde(transparent)]
pub(crate) struct ProtocolRevision(pub(crate) NonZeroU32);

#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd)]
#[serde(transparent)]
pub(crate) struct WorkspaceMetadataRevision(pub(crate) NonZeroU32);

#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum WorkspaceReadCapability {
    ResolveSymbol,
    FindReferences,
    CallHierarchy,
    TypeHierarchy,
    SemanticInsertionPoint,
    SemanticGraph,
    Diagnostics,
    FileOutline,
    WorkspaceSymbolSearch,
    WorkspaceSearch,
    WorkspaceFiles,
    Implementations,
    CodeActions,
    Completions,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum WorkspaceMutationCapability {
    Rename,
    ApplyEdits,
    FileOperations,
    OptimizeImports,
    RefreshWorkspace,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum WorkspaceRuntimeBackendKind {
    Idea,
    Headless,
}

impl WorkspaceRuntimeBackendKind {
    pub(crate) fn metadata_name(self) -> &'static str {
        match self {
            Self::Idea => "idea",
            Self::Headless => "headless",
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct WorkspaceRuntimeIdentity {
    pub(crate) implementation_version: String,
    pub(crate) backend_kind: WorkspaceRuntimeBackendKind,
}

#[derive(Debug, Deserialize, Eq, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct RuntimeCompatibilityFacts {
    pub(crate) plugin_version: String,
    pub(crate) cli_version: String,
    pub(crate) protocol_revision: ProtocolRevision,
    pub(crate) workspace_metadata_revision: WorkspaceMetadataRevision,
    pub(crate) read_capabilities: Vec<WorkspaceReadCapability>,
    pub(crate) mutation_capabilities: Vec<WorkspaceMutationCapability>,
    pub(crate) runtime_identity: WorkspaceRuntimeIdentity,
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub(crate) enum RuntimeCapability {
    Read(WorkspaceReadCapability),
    Mutation(WorkspaceMutationCapability),
}

#[derive(Debug, Eq, PartialEq)]
pub(crate) enum RuntimeCompatibilityAssessment {
    Compatible,
    UpdateRequired {
        requirement: RuntimeCompatibilityUpdateRequirement,
        plugin_version: String,
        cli_version: String,
    },
    MissingCapability {
        capability: RuntimeCapability,
    },
}

#[derive(Debug, Eq, PartialEq)]
pub(crate) enum RuntimeCompatibilityUpdateRequirement {
    UnsupportedReleasePair,
    UnsupportedProtocolRevision {
        actual: ProtocolRevision,
        supported: BTreeSet<ProtocolRevision>,
    },
    UnsupportedWorkspaceMetadataRevision {
        actual: WorkspaceMetadataRevision,
        supported: BTreeSet<WorkspaceMetadataRevision>,
    },
    UnsupportedRuntimeIdentity {
        actual: WorkspaceRuntimeIdentity,
        supported: BTreeSet<WorkspaceRuntimeIdentity>,
    },
    MissingRequiredCapability {
        capability: RuntimeCapability,
    },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RuntimeCompatibilitySource {
    schema_version: u32,
    idea_build_range: IdeaBuildRange,
    supported_pairs: Vec<RuntimeCompatibilitySourcePair>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct IdeaBuildRange {
    since_build: String,
    until_build: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RuntimeCompatibilitySourcePair {
    relation: String,
    plugin_version: String,
    cli_version: String,
    protocol_revision: ProtocolRevision,
    workspace_metadata_revision: WorkspaceMetadataRevision,
    runtime: WorkspaceRuntimeIdentity,
    required_capabilities: Vec<SourceCapability>,
    optional_capabilities: Vec<SourceCapability>,
    evidence: Vec<String>,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd)]
#[serde(tag = "kind", content = "name", rename_all = "SCREAMING_SNAKE_CASE")]
enum SourceCapability {
    Read(WorkspaceReadCapability),
    Mutation(WorkspaceMutationCapability),
}

impl From<SourceCapability> for RuntimeCapability {
    fn from(value: SourceCapability) -> Self {
        match value {
            SourceCapability::Read(capability) => Self::Read(capability),
            SourceCapability::Mutation(capability) => Self::Mutation(capability),
        }
    }
}

pub(crate) fn assess_runtime_compatibility(
    facts: &RuntimeCompatibilityFacts,
    operation_capability: Option<RuntimeCapability>,
) -> Result<RuntimeCompatibilityAssessment> {
    assess_runtime_compatibility_with_plugin_matching(facts, operation_capability, true)
}

pub(crate) fn assess_runtime_compatibility_with_plugin_matching(
    facts: &RuntimeCompatibilityFacts,
    operation_capability: Option<RuntimeCapability>,
    strict_plugin_matching: bool,
) -> Result<RuntimeCompatibilityAssessment> {
    let source: RuntimeCompatibilitySource = serde_json::from_str(include_str!(
        "../../../packaging/jetbrains/runtime-compatibility.json"
    ))
    .map_err(|error| {
        CliError::new(
            "RUNTIME_COMPATIBILITY_SOURCE_INVALID",
            format!("The compiled runtime compatibility source is invalid: {error}"),
        )
    })?;
    validate_runtime_compatibility_source(&source)?;
    let release_rows = source
        .supported_pairs
        .iter()
        .filter(|pair| {
            (!strict_plugin_matching
                || resolve_release_version(&pair.plugin_version) == facts.plugin_version)
                && resolve_release_version(&pair.cli_version) == facts.cli_version
        })
        .collect::<Vec<_>>();
    if release_rows.is_empty() {
        return Ok(RuntimeCompatibilityAssessment::UpdateRequired {
            requirement: RuntimeCompatibilityUpdateRequirement::UnsupportedReleasePair,
            plugin_version: facts.plugin_version.clone(),
            cli_version: facts.cli_version.clone(),
        });
    }
    let protocol_rows = release_rows
        .iter()
        .copied()
        .filter(|pair| pair.protocol_revision == facts.protocol_revision)
        .collect::<Vec<_>>();
    if protocol_rows.is_empty() {
        return Ok(RuntimeCompatibilityAssessment::UpdateRequired {
            requirement: RuntimeCompatibilityUpdateRequirement::UnsupportedProtocolRevision {
                actual: facts.protocol_revision,
                supported: release_rows
                    .iter()
                    .map(|pair| pair.protocol_revision)
                    .collect(),
            },
            plugin_version: facts.plugin_version.clone(),
            cli_version: facts.cli_version.clone(),
        });
    }
    let metadata_rows = protocol_rows
        .iter()
        .copied()
        .filter(|pair| pair.workspace_metadata_revision == facts.workspace_metadata_revision)
        .collect::<Vec<_>>();
    if metadata_rows.is_empty() {
        return Ok(RuntimeCompatibilityAssessment::UpdateRequired {
            requirement:
                RuntimeCompatibilityUpdateRequirement::UnsupportedWorkspaceMetadataRevision {
                    actual: facts.workspace_metadata_revision,
                    supported: protocol_rows
                        .iter()
                        .map(|pair| pair.workspace_metadata_revision)
                        .collect(),
                },
            plugin_version: facts.plugin_version.clone(),
            cli_version: facts.cli_version.clone(),
        });
    }
    let runtime_rows = metadata_rows
        .iter()
        .copied()
        .filter(|pair| {
            pair.runtime.backend_kind == facts.runtime_identity.backend_kind
                && (!strict_plugin_matching
                    || resolve_release_version(&pair.runtime.implementation_version)
                        == facts.runtime_identity.implementation_version)
        })
        .collect::<Vec<_>>();
    let Some(pair) = runtime_rows.first().copied() else {
        return Ok(RuntimeCompatibilityAssessment::UpdateRequired {
            requirement: RuntimeCompatibilityUpdateRequirement::UnsupportedRuntimeIdentity {
                actual: facts.runtime_identity.clone(),
                supported: metadata_rows
                    .iter()
                    .map(|pair| resolved_runtime_identity(&pair.runtime))
                    .collect(),
            },
            plugin_version: facts.plugin_version.clone(),
            cli_version: facts.cli_version.clone(),
        });
    };
    let advertised = facts
        .read_capabilities
        .iter()
        .copied()
        .map(RuntimeCapability::Read)
        .chain(
            facts
                .mutation_capabilities
                .iter()
                .copied()
                .map(RuntimeCapability::Mutation),
        )
        .collect::<BTreeSet<_>>();
    if let Some(missing) = pair
        .required_capabilities
        .iter()
        .copied()
        .map(RuntimeCapability::from)
        .find(|required| !advertised.contains(required))
    {
        return Ok(RuntimeCompatibilityAssessment::UpdateRequired {
            requirement: RuntimeCompatibilityUpdateRequirement::MissingRequiredCapability {
                capability: missing,
            },
            plugin_version: facts.plugin_version.clone(),
            cli_version: facts.cli_version.clone(),
        });
    }
    if let Some(capability) = operation_capability
        && !advertised.contains(&capability)
    {
        return Ok(RuntimeCompatibilityAssessment::MissingCapability { capability });
    }
    Ok(RuntimeCompatibilityAssessment::Compatible)
}

fn resolved_runtime_identity(source: &WorkspaceRuntimeIdentity) -> WorkspaceRuntimeIdentity {
    WorkspaceRuntimeIdentity {
        implementation_version: resolve_release_version(&source.implementation_version),
        backend_kind: source.backend_kind,
    }
}

fn validate_runtime_compatibility_source(source: &RuntimeCompatibilitySource) -> Result<()> {
    let invalid_header = source.schema_version != 1
        || source.idea_build_range.since_build.trim().is_empty()
        || source
            .idea_build_range
            .until_build
            .as_ref()
            .is_some_and(|until| until.trim().is_empty())
        || source.supported_pairs.is_empty();
    if invalid_header {
        return Err(CliError::new(
            "RUNTIME_COMPATIBILITY_SOURCE_INVALID",
            "The compiled runtime compatibility source has an invalid header or no supported rows.",
        ));
    }
    let mut identities = BTreeSet::new();
    for pair in &source.supported_pairs {
        let required = pair
            .required_capabilities
            .iter()
            .copied()
            .collect::<BTreeSet<_>>();
        let optional = pair
            .optional_capabilities
            .iter()
            .copied()
            .collect::<BTreeSet<_>>();
        let identity = (
            resolve_release_version(&pair.plugin_version),
            resolve_release_version(&pair.cli_version),
            pair.protocol_revision,
            pair.workspace_metadata_revision,
            resolve_release_version(&pair.runtime.implementation_version),
            pair.runtime.backend_kind,
        );
        if pair.relation.trim().is_empty()
            || pair.evidence.is_empty()
            || required.len() != pair.required_capabilities.len()
            || optional.len() != pair.optional_capabilities.len()
            || !required.is_disjoint(&optional)
            || !identities.insert(identity)
        {
            return Err(CliError::new(
                "RUNTIME_COMPATIBILITY_SOURCE_INVALID",
                "The compiled runtime compatibility source contains an ambiguous or contradictory row.",
            ));
        }
    }
    Ok(())
}

fn resolve_release_version(version: &str) -> String {
    if version == "{releaseVersion}" {
        cli::version().to_string()
    } else {
        version.to_string()
    }
}
