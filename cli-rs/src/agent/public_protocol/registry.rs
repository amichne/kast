use serde::{Serialize, Serializer};

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub(crate) enum OperationId {
    WorkspaceHome,
    WorkspaceUp,
    WorkspaceRefresh,
    WorkspaceExternalize,
    FileList,
    SymbolSearch,
    SymbolResolve,
    SymbolShow,
    RelationReferences,
    RelationCallsIncoming,
    RelationCallsOutgoing,
    RelationImplementations,
    RelationHierarchySupertypes,
    RelationHierarchySubtypes,
    GraphSummary,
    GraphNodes,
    GraphNeighbors,
    GraphTopology,
    GraphCommunities,
    GraphDerive,
    GraphImpact,
    DiagnosticCheck,
    ChangePlanRename,
    ChangePlanAddFile,
    ChangePlanAddDeclaration,
    ChangePlanReplace,
    ChangeApply,
    ChangeRecover,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum RequestType {
    WorkspaceHome,
    WorkspaceUp,
    WorkspaceRefresh,
    WorkspaceExternalize,
    FileList,
    SymbolSearch,
    SymbolResolve,
    SymbolShow,
    ExactRelation,
    GraphProjection,
    GraphNodes,
    GraphNeighbors,
    GraphDerive,
    GraphImpact,
    DiagnosticCheck,
    ChangePlanRename,
    ChangePlanAddFile,
    ChangePlanAddDeclaration,
    ChangePlanReplace,
    ChangeApply,
    ChangeRecover,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum ResultType {
    WorkspaceHome,
    WorkspaceUp,
    WorkspaceRefresh,
    Externalization,
    Files,
    Matches,
    Resolution,
    Symbol,
    References,
    Relations,
    GraphSummary,
    GraphNodes,
    GraphNeighbors,
    GraphTopology,
    GraphCommunities,
    DerivedTopology,
    Impact,
    Diagnostics,
    ChangePlan,
    MutationReceipt,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum FailureType {
    Installation,
    Workspace,
    PublicProtocol,
    Graph,
    Diagnostic,
    MutationPlan,
    MutationReceipt,
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum Capability {
    InstallationState,
    SemanticDemand,
    WorkspaceRefresh,
    WorkspaceFiles,
    SymbolDiscovery,
    ExactSymbol,
    References,
    CallHierarchy,
    Implementations,
    TypeHierarchy,
    SemanticGraph,
    Diagnostics,
    MutationPlanning,
    MutationApply,
    MutationRecover,
}

impl Capability {
    pub(crate) fn backend_capability(self) -> Option<&'static str> {
        match self {
            Self::WorkspaceFiles => Some("WORKSPACE_FILES"),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum EvidenceRequirement {
    InstallReceipt,
    RuntimeReady,
    CurrentSource,
    CompilerSymbol,
    ExactSelector,
    CompleteRelationships,
    PersistedGraph,
    CompilerDiagnostics,
    MutationAuthority,
    WorkspaceLease,
    DurableRecovery,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum ContinuationType {
    FileList,
    References,
    CallsIncoming,
    CallsOutgoing,
    Implementations,
    HierarchySupertypes,
    HierarchySubtypes,
    GraphNodes,
    GraphImpact,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub(crate) enum Paging {
    Unpaged,
    Continuation { continuation_type: ContinuationType },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum PublicValueType {
    SymbolQuery,
    SymbolSelector,
    GraphNodeSelector,
    Continuation,
    WorkspaceKotlinPath,
    PlanId,
    RecoveryId,
    ExternalFailureId,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RpcBinding {
    pub value_type: PublicValueType,
    pub rpc_field: &'static str,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CliRoute {
    pub segments: &'static [&'static str],
    pub syntax: &'static str,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct OperationDefinition {
    pub id: OperationId,
    #[serde(skip)]
    stable_id: &'static str,
    pub cli: CliRoute,
    pub request_type: RequestType,
    pub result_type: ResultType,
    pub result_discriminators: &'static [&'static str],
    pub failure_type: FailureType,
    pub capability: Capability,
    pub evidence: &'static [EvidenceRequirement],
    pub paging: Paging,
    pub examples: &'static [&'static str],
    pub successors: &'static [OperationId],
    pub rpc_methods: &'static [&'static str],
    pub rpc_bindings: &'static [RpcBinding],
}

macro_rules! op {
    ($id:ident, $name:literal, [$($segment:literal),*], $syntax:literal,
     $request:ident, $result:ident, [$($discriminator:literal),+], $failure:ident,
     $capability:ident, [$($evidence:ident),*], $paging:expr, [$($successor:ident),*],
     [$($method:literal),*], [$($value:ident => $field:literal),*]) => {
        OperationDefinition {
            id: OperationId::$id,
            stable_id: $name,
            cli: CliRoute { segments: &[$($segment),*], syntax: $syntax },
            request_type: RequestType::$request,
            result_type: ResultType::$result,
            result_discriminators: &[$($discriminator),+],
            failure_type: FailureType::$failure,
            capability: Capability::$capability,
            evidence: &[$(EvidenceRequirement::$evidence),*],
            paging: $paging,
            examples: &[$syntax],
            successors: &[$(OperationId::$successor),*],
            rpc_methods: &[$($method),*],
            rpc_bindings: &[$(RpcBinding { value_type: PublicValueType::$value, rpc_field: $field }),*],
        }
    };
}

macro_rules! continuation {
    ($kind:ident) => {
        Paging::Continuation {
            continuation_type: ContinuationType::$kind,
        }
    };
}

#[rustfmt::skip]
impl OperationId {
    pub(crate) const ALL: [Self; 28] = [
        Self::WorkspaceHome, Self::WorkspaceUp, Self::WorkspaceRefresh,
        Self::WorkspaceExternalize, Self::FileList, Self::SymbolSearch, Self::SymbolResolve,
        Self::SymbolShow, Self::RelationReferences, Self::RelationCallsIncoming,
        Self::RelationCallsOutgoing, Self::RelationImplementations,
        Self::RelationHierarchySupertypes, Self::RelationHierarchySubtypes, Self::GraphSummary,
        Self::GraphNodes, Self::GraphNeighbors, Self::GraphTopology, Self::GraphCommunities,
        Self::GraphDerive, Self::GraphImpact, Self::DiagnosticCheck, Self::ChangePlanRename,
        Self::ChangePlanAddFile, Self::ChangePlanAddDeclaration, Self::ChangePlanReplace,
        Self::ChangeApply, Self::ChangeRecover,
    ];

    pub(crate) fn as_str(self) -> &'static str {
        self.definition().stable_id
    }

    pub(crate) fn definition(self) -> OperationDefinition {
        use Paging::Unpaged;
        match self {
            Self::WorkspaceHome => op!(WorkspaceHome, "workspace.home", [], "kast", WorkspaceHome, WorkspaceHome, ["home"], Installation, InstallationState, [InstallReceipt], Unpaged, [WorkspaceUp, WorkspaceRefresh, FileList, SymbolSearch, DiagnosticCheck], [], []),
            Self::WorkspaceUp => op!(WorkspaceUp, "workspace.up", ["up"], "kast up", WorkspaceUp, WorkspaceUp, ["workspace-up"], Workspace, SemanticDemand, [RuntimeReady, CurrentSource], Unpaged, [WorkspaceRefresh, FileList, SymbolSearch, DiagnosticCheck], [], []),
            Self::WorkspaceRefresh => op!(WorkspaceRefresh, "workspace.refresh", ["workspace", "refresh"], "kast workspace refresh --file src/main/kotlin/example/Widget.kt", WorkspaceRefresh, WorkspaceRefresh, ["workspace-refresh"], Workspace, WorkspaceRefresh, [RuntimeReady, CurrentSource], Unpaged, [FileList, SymbolSearch, DiagnosticCheck], ["raw/workspace-refresh", "raw/semantic-graph"], [WorkspaceKotlinPath => "filePaths"]),
            Self::WorkspaceExternalize => op!(WorkspaceExternalize, "workspace.externalize", ["workspace", "externalize"], "kast workspace externalize --failure-id <FAILURE_ID>", WorkspaceExternalize, Externalization, ["externalization"], Workspace, WorkspaceRefresh, [RuntimeReady, CurrentSource], Unpaged, [WorkspaceRefresh], ["raw/workspace-refresh"], [ExternalFailureId => "externalFailureIds"]),
            Self::FileList => op!(FileList, "file.list", ["file", "list"], "kast file list --match '**/*.kt'", FileList, Files, ["files"], PublicProtocol, WorkspaceFiles, [CurrentSource], continuation!(FileList), [FileList, SymbolSearch], ["raw/workspace-files"], [Continuation => "pageToken"]),
            Self::SymbolSearch => op!(SymbolSearch, "symbol.search", ["symbol", "search"], "kast symbol search --query Widget", SymbolSearch, Matches, ["matches"], PublicProtocol, SymbolDiscovery, [CompilerSymbol], Unpaged, [SymbolResolve, SymbolShow, RelationReferences, RelationCallsIncoming, RelationCallsOutgoing, RelationImplementations, RelationHierarchySupertypes, RelationHierarchySubtypes, GraphImpact], ["symbol/discover"], [SymbolQuery => "symbol"]),
            Self::SymbolResolve => op!(SymbolResolve, "symbol.resolve", ["symbol", "resolve"], "kast symbol resolve --query 'example.Widget.render()'", SymbolResolve, Resolution, ["resolved", "not-found", "ambiguous"], PublicProtocol, SymbolDiscovery, [CompilerSymbol], Unpaged, [SymbolShow, RelationReferences, RelationCallsIncoming, RelationCallsOutgoing, RelationImplementations, RelationHierarchySupertypes, RelationHierarchySubtypes, GraphImpact], ["symbol/resolve"], [SymbolQuery => "symbol"]),
            Self::SymbolShow => op!(SymbolShow, "symbol.show", ["symbol", "show"], "kast symbol show --selector <SELECTOR>", SymbolShow, Symbol, ["symbol"], PublicProtocol, ExactSymbol, [ExactSelector, CompilerSymbol], Unpaged, [RelationReferences, RelationCallsIncoming, RelationCallsOutgoing, RelationImplementations, RelationHierarchySupertypes, RelationHierarchySubtypes, GraphImpact, ChangePlanRename, ChangePlanReplace], ["selector/identity"], [SymbolSelector => "selectorHandle"]),
            Self::RelationReferences => op!(RelationReferences, "relation.references", ["relation", "references"], "kast relation references --selector <SELECTOR>", ExactRelation, References, ["references"], PublicProtocol, References, [ExactSelector, CompleteRelationships], continuation!(References), [RelationReferences], ["selector/identity", "symbol/references"], [SymbolSelector => "selectorHandle", Continuation => "pageToken"]),
            Self::RelationCallsIncoming => op!(RelationCallsIncoming, "relation.calls.incoming", ["relation", "calls", "incoming"], "kast relation calls incoming --selector <SELECTOR>", ExactRelation, Relations, ["relations"], PublicProtocol, CallHierarchy, [ExactSelector, CompleteRelationships], continuation!(CallsIncoming), [RelationCallsIncoming], ["selector/identity", "symbol/callers"], [SymbolSelector => "selectorHandle", Continuation => "pageToken"]),
            Self::RelationCallsOutgoing => op!(RelationCallsOutgoing, "relation.calls.outgoing", ["relation", "calls", "outgoing"], "kast relation calls outgoing --selector <SELECTOR>", ExactRelation, Relations, ["relations"], PublicProtocol, CallHierarchy, [ExactSelector, CompleteRelationships], continuation!(CallsOutgoing), [RelationCallsOutgoing], ["selector/identity", "symbol/callers"], [SymbolSelector => "selectorHandle", Continuation => "pageToken"]),
            Self::RelationImplementations => op!(RelationImplementations, "relation.implementations", ["relation", "implementations"], "kast relation implementations --selector <SELECTOR>", ExactRelation, Relations, ["relations"], PublicProtocol, Implementations, [ExactSelector, CompleteRelationships], continuation!(Implementations), [RelationImplementations], ["selector/identity", "symbol/implementations"], [SymbolSelector => "selectorHandle", Continuation => "pageToken"]),
            Self::RelationHierarchySupertypes => op!(RelationHierarchySupertypes, "relation.hierarchy.supertypes", ["relation", "hierarchy", "supertypes"], "kast relation hierarchy supertypes --selector <SELECTOR>", ExactRelation, Relations, ["relations"], PublicProtocol, TypeHierarchy, [ExactSelector, CompleteRelationships], continuation!(HierarchySupertypes), [RelationHierarchySupertypes], ["selector/identity", "symbol/hierarchy"], [SymbolSelector => "selectorHandle", Continuation => "pageToken"]),
            Self::RelationHierarchySubtypes => op!(RelationHierarchySubtypes, "relation.hierarchy.subtypes", ["relation", "hierarchy", "subtypes"], "kast relation hierarchy subtypes --selector <SELECTOR>", ExactRelation, Relations, ["relations"], PublicProtocol, TypeHierarchy, [ExactSelector, CompleteRelationships], continuation!(HierarchySubtypes), [RelationHierarchySubtypes], ["selector/identity", "symbol/hierarchy"], [SymbolSelector => "selectorHandle", Continuation => "pageToken"]),
            Self::GraphSummary => op!(GraphSummary, "graph.summary", ["graph", "summary"], "kast graph summary --scope symbol", GraphProjection, GraphSummary, ["graph-summary"], Graph, SemanticGraph, [PersistedGraph], Unpaged, [GraphNodes, GraphTopology, GraphCommunities], ["raw/semantic-graph"], []),
            Self::GraphNodes => op!(GraphNodes, "graph.nodes", ["graph", "nodes"], "kast graph nodes", GraphNodes, GraphNodes, ["graph-nodes"], Graph, SemanticGraph, [PersistedGraph], continuation!(GraphNodes), [GraphNodes, GraphNeighbors], ["raw/semantic-graph"], [Continuation => "afterId"]),
            Self::GraphNeighbors => op!(GraphNeighbors, "graph.neighbors", ["graph", "neighbors"], "kast graph neighbors --node-selector <NODE_SELECTOR>", GraphNeighbors, GraphNeighbors, ["graph-neighbors"], Graph, SemanticGraph, [PersistedGraph], Unpaged, [GraphNeighbors], ["raw/semantic-graph"], [GraphNodeSelector => "symbol"]),
            Self::GraphTopology => op!(GraphTopology, "graph.topology", ["graph", "topology"], "kast graph topology --scope symbol", GraphProjection, GraphTopology, ["graph-topology"], Graph, SemanticGraph, [PersistedGraph], Unpaged, [GraphCommunities], ["raw/semantic-graph"], []),
            Self::GraphCommunities => op!(GraphCommunities, "graph.communities", ["graph", "communities"], "kast graph communities --scope symbol", GraphProjection, GraphCommunities, ["graph-communities"], Graph, SemanticGraph, [PersistedGraph], Unpaged, [GraphNodes], ["raw/semantic-graph"], []),
            Self::GraphDerive => op!(GraphDerive, "graph.derive", ["graph", "derive"], "kast graph derive --experimental-derived-topology --out .kast/topology.json", GraphDerive, DerivedTopology, ["derived-topology"], Graph, SemanticGraph, [PersistedGraph], Unpaged, [GraphTopology], [], [WorkspaceKotlinPath => "out"]),
            Self::GraphImpact => op!(GraphImpact, "graph.impact", ["graph", "impact"], "kast graph impact --selector <SELECTOR>", GraphImpact, Impact, ["impact", "impact-qualified"], PublicProtocol, SemanticGraph, [ExactSelector, PersistedGraph], continuation!(GraphImpact), [GraphImpact], ["selector/identity", "raw/semantic-graph"], [SymbolSelector => "selectorHandle", Continuation => "pageToken"]),
            Self::DiagnosticCheck => op!(DiagnosticCheck, "diagnostic.check", ["diagnostic", "check"], "kast diagnostic check --file src/main/kotlin/example/Widget.kt", DiagnosticCheck, Diagnostics, ["diagnostics"], Diagnostic, Diagnostics, [CurrentSource, CompilerDiagnostics], Unpaged, [ChangePlanRename, ChangePlanReplace], ["raw/diagnostics"], [WorkspaceKotlinPath => "filePaths"]),
            Self::ChangePlanRename => op!(ChangePlanRename, "change.plan.rename", ["change", "plan", "rename"], "kast change plan rename --selector <SELECTOR> --name Renamed", ChangePlanRename, ChangePlan, ["change-plan"], MutationPlan, MutationPlanning, [ExactSelector, MutationAuthority], Unpaged, [ChangeApply], ["selector/identity", "raw/rename"], [SymbolSelector => "selectorHandle"]),
            Self::ChangePlanAddFile => op!(ChangePlanAddFile, "change.plan.add-file", ["change", "plan", "add-file"], "printf 'class Widget' | kast change plan add-file --file src/main/kotlin/example/Widget.kt", ChangePlanAddFile, ChangePlan, ["change-plan"], MutationPlan, MutationPlanning, [CurrentSource, MutationAuthority], Unpaged, [ChangeApply], ["change/plan-add-file"], [WorkspaceKotlinPath => "filePath"]),
            Self::ChangePlanAddDeclaration => op!(ChangePlanAddDeclaration, "change.plan.add-declaration", ["change", "plan", "add-declaration"], "printf 'fun render() = Unit' | kast change plan add-declaration --file src/main/kotlin/example/Widget.kt", ChangePlanAddDeclaration, ChangePlan, ["change-plan"], MutationPlan, MutationPlanning, [CurrentSource, MutationAuthority], Unpaged, [ChangeApply], ["change/plan-add-declaration"], [WorkspaceKotlinPath => "filePath"]),
            Self::ChangePlanReplace => op!(ChangePlanReplace, "change.plan.replace", ["change", "plan", "replace"], "printf 'fun render() = Unit' | kast change plan replace --selector <SELECTOR>", ChangePlanReplace, ChangePlan, ["change-plan"], MutationPlan, MutationPlanning, [ExactSelector, MutationAuthority], Unpaged, [ChangeApply], ["selector/identity", "raw/plan-replacement"], [SymbolSelector => "selectorHandle"]),
            Self::ChangeApply => op!(ChangeApply, "change.apply", ["change", "apply"], "kast change apply --plan-id <PLAN_ID>", ChangeApply, MutationReceipt, ["mutation-receipt"], MutationReceipt, MutationApply, [MutationAuthority, WorkspaceLease], Unpaged, [ChangeRecover], ["change/apply-add-file", "change/apply-add-declaration", "raw/apply-edits", "raw/verify-mutation-postcondition"], [PlanId => "planId"]),
            Self::ChangeRecover => op!(ChangeRecover, "change.recover", ["change", "recover"], "kast change recover --recovery-id <RECOVERY_ID>", ChangeRecover, MutationReceipt, ["mutation-receipt"], MutationReceipt, MutationRecover, [DurableRecovery, WorkspaceLease], Unpaged, [ChangeApply], ["change/apply-add-file", "raw/inspect-mutation-scratch", "raw/recover-mutation-scratch"], [RecoveryId => "recoveryId"]),
        }
    }
}

impl Serialize for OperationId {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

pub(crate) fn operation_definitions() -> impl ExactSizeIterator<Item = OperationDefinition> {
    OperationId::ALL.into_iter().map(OperationId::definition)
}
