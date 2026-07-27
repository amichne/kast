#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct RepositoryRpcRequest {
    jsonrpc: String,
    id: Value,
    method: String,
    params: Value,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RepositoryScope {
    #[serde(default)]
    language: Option<String>,
    #[serde(default)]
    module: Option<String>,
    #[serde(default)]
    source_set: Option<String>,
    #[serde(default)]
    relations: Vec<RepositoryRelationKind>,
    #[serde(default)]
    direction: Option<RepositoryDirection>,
    #[serde(default)]
    max_depth: Option<usize>,
    #[serde(default)]
    projection: Option<RepositoryArchitectureProjection>,
    #[serde(default)]
    metric: Option<RepositoryArchitectureMetric>,
    #[serde(default)]
    sources: Vec<RepositoryContextSource>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct GraphCoverageScope {
    #[serde(default)]
    language: Option<String>,
    #[serde(default)]
    module: Option<String>,
    #[serde(default)]
    source_set: Option<String>,
}

impl From<GraphCoverageScope> for RepositoryScope {
    fn from(scope: GraphCoverageScope) -> Self {
        Self {
            language: scope.language,
            module: scope.module,
            source_set: scope.source_set,
            ..Self::default()
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct GraphCoverageParams {
    #[serde(default, rename = "workspaceRoot")]
    _workspace_root: Option<String>,
    #[serde(default)]
    scope: GraphCoverageScope,
    #[serde(default)]
    continuation: Option<GraphCoverageContinuation>,
    #[serde(default = "default_file_limit")]
    limit: usize,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
struct GraphCoverageContinuation(String);

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct GraphCoverageContinuationClaims {
    #[serde(rename = "v")]
    schema_version: u32,
    #[serde(rename = "g")]
    graph_generation: u64,
    #[serde(rename = "q")]
    query_sha256: String,
    #[serde(rename = "c")]
    coverage_sha256: String,
    #[serde(rename = "x")]
    next_offset: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RepositoryLimits {
    depth: usize,
    results: usize,
    evidence: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum RepositoryIntent {
    Resolve,
    Path,
    IncomingImpact,
    OutgoingImpact,
    Architecture,
    ContextRelationship,
}

impl RepositoryIntent {
    fn canonical(self) -> &'static str {
        match self {
            Self::Resolve => "RESOLVE",
            Self::Path => "PATH",
            Self::IncomingImpact => "INCOMING_IMPACT",
            Self::OutgoingImpact => "OUTGOING_IMPACT",
            Self::Architecture => "ARCHITECTURE",
            Self::ContextRelationship => "CONTEXT_RELATIONSHIP",
        }
    }

    fn is_graph_relationship(self) -> bool {
        matches!(
            self,
            Self::Path | Self::IncomingImpact | Self::OutgoingImpact
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RepositoryRelationKind {
    Calls,
    CaseOf,
    Contains,
    Delegates,
    ExpectActual,
    Implements,
    Inherits,
    Method,
    Overrides,
    References,
    SealedMember,
}

impl RepositoryRelationKind {
    fn canonical(self) -> &'static str {
        match self {
            Self::Calls => "CALLS",
            Self::CaseOf => "CASE_OF",
            Self::Contains => "CONTAINS",
            Self::Delegates => "DELEGATES",
            Self::ExpectActual => "EXPECT_ACTUAL",
            Self::Implements => "IMPLEMENTS",
            Self::Inherits => "INHERITS",
            Self::Method => "METHOD",
            Self::Overrides => "OVERRIDES",
            Self::References => "REFERENCES",
            Self::SealedMember => "SEALED_MEMBER",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RepositoryDirection {
    Incoming,
    Outgoing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RepositoryArchitectureProjection {
    RuntimeCalls,
    SymbolReferences,
    TypeDependencies,
    InterfaceImplementation,
    ModuleDependencies,
    ContainmentOwnership,
}

impl RepositoryArchitectureProjection {
    fn canonical(self) -> &'static str {
        match self {
            Self::RuntimeCalls => "RUNTIME_CALLS",
            Self::SymbolReferences => "SYMBOL_REFERENCES",
            Self::TypeDependencies => "TYPE_DEPENDENCIES",
            Self::InterfaceImplementation => "INTERFACE_IMPLEMENTATION",
            Self::ModuleDependencies => "MODULE_DEPENDENCIES",
            Self::ContainmentOwnership => "CONTAINMENT_OWNERSHIP",
        }
    }

    fn relation_kinds(self) -> &'static [RepositoryRelationKind] {
        match self {
            Self::RuntimeCalls => &[RepositoryRelationKind::Calls],
            Self::SymbolReferences | Self::TypeDependencies => {
                &[RepositoryRelationKind::References]
            }
            Self::InterfaceImplementation => &[
                RepositoryRelationKind::CaseOf,
                RepositoryRelationKind::Implements,
                RepositoryRelationKind::Inherits,
                RepositoryRelationKind::Overrides,
                RepositoryRelationKind::SealedMember,
            ],
            Self::ModuleDependencies => &[
                RepositoryRelationKind::Calls,
                RepositoryRelationKind::Implements,
                RepositoryRelationKind::Inherits,
                RepositoryRelationKind::Overrides,
                RepositoryRelationKind::References,
            ],
            Self::ContainmentOwnership => &[
                RepositoryRelationKind::Contains,
                RepositoryRelationKind::Method,
            ],
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RepositoryArchitectureMetric {
    Scc,
    Communities,
    Bridges,
    PublicApiConsumers,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum RepositoryContextSource {
    Markdown,
    Gradle,
    Schema,
    Workflow,
    Rust,
}

impl RepositoryContextSource {
    fn priority(self) -> usize {
        match self {
            Self::Markdown => 0,
            Self::Gradle => 1,
            Self::Schema => 2,
            Self::Workflow => 3,
            Self::Rust => 4,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RepositoryContextRelationKind {
    MentionsSymbol,
    Documents,
    ConfiguresModule,
    DeclaresDependency,
    Generates,
    ConsumesSchema,
    ImplementsProtocol,
    Supersedes,
    ConflictsWith,
}

impl RepositoryArchitectureMetric {
    fn canonical(self) -> &'static str {
        match self {
            Self::Scc => "STRONGLY_CONNECTED_COMPONENT",
            Self::Communities => "COMMUNITIES",
            Self::Bridges => "BRIDGES",
            Self::PublicApiConsumers => "PUBLIC_API_CONSUMERS",
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RepositoryQueryParams {
    #[serde(default, rename = "workspaceRoot")]
    _workspace_root: Option<String>,
    question: String,
    intent: RepositoryIntent,
    #[serde(default)]
    canonical_key: Option<String>,
    #[serde(default)]
    scope: RepositoryScope,
    limits: RepositoryLimits,
    #[serde(default)]
    continuation: Option<RepositoryTraversalContinuation>,
    #[serde(default)]
    evidence_continuation: Option<RepositoryEvidenceContinuation>,
}

struct ValidatedRepositoryQueryParams {
    question: String,
    intent: RepositoryIntent,
    canonical_key: Option<String>,
    scope: RepositoryScope,
    limits: RepositoryLimits,
    continuation: Option<RepositoryTraversalContinuation>,
    evidence_continuation: Option<RepositoryEvidenceContinuation>,
}

impl RepositoryQueryParams {
    fn validated(self) -> Result<ValidatedRepositoryQueryParams> {
        validate_scope(&self.scope)?;
        validate_limits(&self.limits)?;
        if self.question.trim().is_empty() {
            return Err(invalid_repository_query(
                "repository question must not be blank",
            ));
        }
        if self
            .scope
            .max_depth
            .is_some_and(|max_depth| max_depth > self.limits.depth)
        {
            return Err(invalid_repository_query(
                "scope.maxDepth/--max-depth cannot exceed limits.depth/--depth; lower maxDepth or raise depth to at most 6",
            ));
        }
        let graph_intent = self.intent.is_graph_relationship();
        if self.canonical_key.is_some() && self.intent != RepositoryIntent::Resolve {
            return Err(invalid_repository_query(
                "canonicalKey/--canonical-key is valid only with intent=resolve",
            ));
        }
        if (!self.scope.relations.is_empty() || self.scope.max_depth.is_some()) && !graph_intent {
            return Err(invalid_repository_query(
                "scope.relations and scope.maxDepth require intent=path, incoming_impact, or outgoing_impact",
            ));
        }
        if self.scope.direction.is_some()
            && !matches!(
                self.intent,
                RepositoryIntent::Path | RepositoryIntent::Architecture
            )
        {
            return Err(invalid_repository_query(
                "scope.direction is valid only with intent=path or architecture",
            ));
        }
        if self.intent == RepositoryIntent::Architecture && self.scope.projection.is_none() {
            return Err(invalid_repository_query(
                "intent=architecture requires scope.projection/--projection",
            ));
        }
        if (self.scope.projection.is_some() || self.scope.metric.is_some())
            && self.intent != RepositoryIntent::Architecture
        {
            return Err(invalid_repository_query(
                "scope.projection and scope.metric require intent=architecture",
            ));
        }
        if !self.scope.sources.is_empty() && self.intent != RepositoryIntent::ContextRelationship {
            return Err(invalid_repository_query(
                "scope.sources requires intent=context_relationship",
            ));
        }
        if self.continuation.is_some() && self.evidence_continuation.is_some() {
            return Err(invalid_repository_continuation(
                "Repository traversal and evidence continuations cannot be consumed together.",
            ));
        }
        if self.continuation.is_some() && !graph_intent {
            return Err(invalid_repository_continuation(
                "Repository traversal continuation requires a graph relationship query.",
            ));
        }
        if self.evidence_continuation.is_some() && !graph_intent {
            return Err(invalid_repository_continuation(
                "Repository evidence continuation requires a graph relationship query.",
            ));
        }
        Ok(ValidatedRepositoryQueryParams {
            question: self.question,
            intent: self.intent,
            canonical_key: self.canonical_key,
            scope: self.scope,
            limits: self.limits,
            continuation: self.continuation,
            evidence_continuation: self.evidence_continuation,
        })
    }
}
