#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspaceEvidenceSource {
    Manifest,
    PackageMetadata,
    GradleProjectModel,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct BackendModuleName(String);

impl BackendModuleName {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty() && value.trim() == value && !value.chars().any(char::is_control))
            .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct BackendWorkspaceSnapshotToken(String);

impl BackendWorkspaceSnapshotToken {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty() && value.trim() == value && !value.chars().any(char::is_control))
            .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct BackendWorkspacePageToken(String);

impl BackendWorkspacePageToken {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty() && value.trim() == value && !value.chars().any(char::is_control))
            .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspaceRequestedKindDomain {
    SourceOnly,
    ScriptOnly,
    Mixed,
}

impl WorkspaceRequestedKindDomain {
    pub(crate) fn includes_sources(self) -> bool {
        matches!(self, Self::SourceOnly | Self::Mixed)
    }

    pub(crate) fn includes_scripts(self) -> bool {
        matches!(self, Self::ScriptOnly | Self::Mixed)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum BackendWorkspaceCoverage {
    Complete,
    Partial,
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum BackendModuleCoverage {
    Complete,
    Partial,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BackendModuleInventory {
    name: BackendModuleName,
    source_roots: BTreeSet<WorkspaceContainedRoot>,
    content_roots: BTreeSet<WorkspaceContainedRoot>,
    dependency_module_names: BTreeSet<BackendModuleName>,
    declared_file_count: usize,
    coverage: BackendModuleCoverage,
}

impl BackendModuleInventory {
    pub(super) fn new(
        name: BackendModuleName,
        source_roots: BTreeSet<WorkspaceContainedRoot>,
        content_roots: BTreeSet<WorkspaceContainedRoot>,
        dependency_module_names: BTreeSet<BackendModuleName>,
        declared_file_count: usize,
        coverage: BackendModuleCoverage,
    ) -> Self {
        Self {
            name,
            source_roots,
            content_roots,
            dependency_module_names,
            declared_file_count,
            coverage,
        }
    }

    pub(crate) fn name(&self) -> &BackendModuleName {
        &self.name
    }

    pub(crate) fn source_roots(&self) -> &BTreeSet<WorkspaceContainedRoot> {
        &self.source_roots
    }

    pub(crate) fn content_roots(&self) -> &BTreeSet<WorkspaceContainedRoot> {
        &self.content_roots
    }

    pub(crate) fn declared_file_count(&self) -> usize {
        self.declared_file_count
    }

    pub(crate) fn coverage(&self) -> BackendModuleCoverage {
        self.coverage
    }
}

#[cfg(test)]
impl BackendModuleInventory {
    pub(crate) fn dependency_module_names(&self) -> &BTreeSet<BackendModuleName> {
        &self.dependency_module_names
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BackendModuleLeaseFingerprint {
    source_roots: BTreeSet<WorkspaceContainedRoot>,
    content_roots: BTreeSet<WorkspaceContainedRoot>,
    dependency_module_names: BTreeSet<BackendModuleName>,
    declared_file_count: usize,
    coverage: BackendModuleCoverage,
}

impl BackendModuleLeaseFingerprint {
    fn from_inventory(module: &BackendModuleInventory) -> Self {
        Self {
            source_roots: module.source_roots.clone(),
            content_roots: module.content_roots.clone(),
            dependency_module_names: module.dependency_module_names.clone(),
            declared_file_count: module.declared_file_count,
            coverage: module.coverage,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BackendWorkspaceStamp {
    files: BTreeMap<WorkspaceFilePath, BTreeSet<BackendModuleName>>,
    modules: BTreeMap<BackendModuleName, BackendModuleLeaseFingerprint>,
    coverage: BackendWorkspaceCoverage,
    limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
}

impl BackendWorkspaceStamp {
    fn from_inventory(inventory: &BackendWorkspaceInventory) -> Self {
        Self {
            files: inventory.files.clone(),
            modules: inventory
                .modules
                .iter()
                .map(|(name, module)| {
                    (
                        name.clone(),
                        BackendModuleLeaseFingerprint::from_inventory(module),
                    )
                })
                .collect(),
            coverage: inventory.coverage,
            limitations: inventory.limitations.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BackendWorkspaceInventory {
    files: BTreeMap<WorkspaceFilePath, BTreeSet<BackendModuleName>>,
    modules: BTreeMap<BackendModuleName, BackendModuleInventory>,
    coverage: BackendWorkspaceCoverage,
    snapshot_token: Option<BackendWorkspaceSnapshotToken>,
    limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
}

impl BackendWorkspaceInventory {
    pub(super) fn new(
        files: BTreeMap<WorkspaceFilePath, BTreeSet<BackendModuleName>>,
        modules: BTreeMap<BackendModuleName, BackendModuleInventory>,
        coverage: BackendWorkspaceCoverage,
        snapshot_token: Option<BackendWorkspaceSnapshotToken>,
        limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    ) -> Self {
        Self {
            files,
            modules,
            coverage,
            snapshot_token,
            limitations,
        }
    }

    pub(crate) fn files(&self) -> &BTreeMap<WorkspaceFilePath, BTreeSet<BackendModuleName>> {
        &self.files
    }

    pub(crate) fn modules(&self) -> &BTreeMap<BackendModuleName, BackendModuleInventory> {
        &self.modules
    }

    pub(crate) fn coverage(&self) -> BackendWorkspaceCoverage {
        self.coverage
    }

    pub(crate) fn snapshot_token(&self) -> Option<&BackendWorkspaceSnapshotToken> {
        self.snapshot_token.as_ref()
    }

    pub(crate) fn stamp(&self) -> Option<BackendWorkspaceStamp> {
        self.snapshot_token
            .as_ref()
            .map(|_| BackendWorkspaceStamp::from_inventory(self))
    }

    pub(crate) fn limitations(&self) -> &BTreeMap<WorkspaceInventoryLimitationCode, usize> {
        &self.limitations
    }
}
