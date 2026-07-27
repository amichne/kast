#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WorkspaceCoverageDimension {
    Complete,
    Partial,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct WorkspaceMatchCoverage {
    candidate_inventory: WorkspaceCoverageDimension,
    filter_evidence: WorkspaceCoverageDimension,
}

impl WorkspaceMatchCoverage {
    pub(super) fn from_dimensions(
        candidate_inventory: WorkspaceCoverageDimension,
        filter_evidence: WorkspaceCoverageDimension,
    ) -> Self {
        Self {
            candidate_inventory,
            filter_evidence,
        }
    }

    pub(crate) fn candidate_inventory(self) -> WorkspaceCoverageDimension {
        self.candidate_inventory
    }
}

#[cfg(test)]
impl WorkspaceMatchCoverage {
    pub(crate) fn complete() -> Self {
        Self {
            candidate_inventory: WorkspaceCoverageDimension::Complete,
            filter_evidence: WorkspaceCoverageDimension::Complete,
        }
    }

    pub(crate) fn filter_evidence(self) -> WorkspaceCoverageDimension {
        self.filter_evidence
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct WorkspaceIndexSnapshot {
    files: Vec<WorkspaceInventoryFile>,
    stamp: SourceIndexSnapshotStamp,
    limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    coverage: WorkspaceMatchCoverage,
}

impl WorkspaceIndexSnapshot {
    pub(super) fn new(
        mut files: Vec<WorkspaceInventoryFile>,
        stamp: SourceIndexSnapshotStamp,
        limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
        coverage: WorkspaceMatchCoverage,
    ) -> Self {
        files.sort_by(|left, right| left.path.cmp(&right.path));
        Self {
            files,
            stamp,
            limitations,
            coverage,
        }
    }

    pub(crate) fn files(&self) -> &[WorkspaceInventoryFile] {
        &self.files
    }

    pub(crate) fn stamp(&self) -> &SourceIndexSnapshotStamp {
        &self.stamp
    }

    pub(crate) fn limitations(&self) -> &BTreeMap<WorkspaceInventoryLimitationCode, usize> {
        &self.limitations
    }

    pub(crate) fn coverage(&self) -> WorkspaceMatchCoverage {
        self.coverage
    }
}

#[cfg(test)]
impl WorkspaceIndexSnapshot {
    pub(crate) fn limitation_count(&self, code: WorkspaceInventoryLimitationCode) -> usize {
        self.limitations.get(&code).copied().unwrap_or_default()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct WorkspaceIndexReadFailure {
    limitation: WorkspaceInventoryLimitationCode,
    detail: String,
}

impl WorkspaceIndexReadFailure {
    pub(super) fn new(limitation: WorkspaceInventoryLimitationCode, detail: String) -> Self {
        Self { limitation, detail }
    }

    pub(crate) fn limitation(&self) -> WorkspaceInventoryLimitationCode {
        self.limitation
    }

    pub(crate) fn detail(&self) -> &str {
        &self.detail
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum WorkspaceIndexRead {
    Snapshot(WorkspaceIndexSnapshot),
    Unavailable(WorkspaceIndexReadFailure),
    Incompatible(WorkspaceIndexReadFailure),
}
