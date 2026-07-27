#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SourceIndexGeneration(u64);

impl SourceIndexGeneration {
    pub(super) fn try_from_database(value: i64) -> Option<Self> {
        u64::try_from(value).ok().map(Self)
    }
}

impl SourceIndexGeneration {
    pub(crate) fn value(self) -> u64 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SourceIndexPendingCount(u64);

impl SourceIndexPendingCount {
    pub(super) fn try_from_database(value: i64) -> Option<Self> {
        u64::try_from(value).ok().map(Self)
    }

    pub(crate) fn value(self) -> u64 {
        self.0
    }

    pub(crate) fn is_empty(self) -> bool {
        self.0 == 0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SourceIndexModuleName(String);

impl SourceIndexModuleName {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty() && value.trim() == value && !value.chars().any(char::is_control))
            .then_some(Self(value))
    }
}

impl SourceIndexModuleName {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum SourceIndexProgressStatus {
    Pending,
    Indexing,
    Complete,
    Failed,
}

impl SourceIndexProgressStatus {
    pub(super) fn parse(value: &str) -> Option<Self> {
        match value {
            "PENDING" => Some(Self::Pending),
            "INDEXING" => Some(Self::Indexing),
            "COMPLETE" => Some(Self::Complete),
            "FAILED" => Some(Self::Failed),
            _ => None,
        }
    }

    pub(crate) fn canonical(self) -> &'static str {
        match self {
            Self::Pending => "PENDING",
            Self::Indexing => "INDEXING",
            Self::Complete => "COMPLETE",
            Self::Failed => "FAILED",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SourceIndexModuleProgress {
    module_name: SourceIndexModuleName,
    status: SourceIndexProgressStatus,
    indexed_file_count: u64,
    total_file_count: u64,
}

impl SourceIndexModuleProgress {
    pub(super) fn from_database(
        module_name: String,
        status: String,
        indexed_file_count: i64,
        total_file_count: i64,
    ) -> Option<Self> {
        Some(Self {
            module_name: SourceIndexModuleName::parse(module_name)?,
            status: SourceIndexProgressStatus::parse(&status)?,
            indexed_file_count: u64::try_from(indexed_file_count).ok()?,
            total_file_count: u64::try_from(total_file_count).ok()?,
        })
    }

    pub(crate) fn status(&self) -> SourceIndexProgressStatus {
        self.status
    }

    pub(crate) fn indexed_file_count(&self) -> u64 {
        self.indexed_file_count
    }

    pub(crate) fn total_file_count(&self) -> u64 {
        self.total_file_count
    }

    pub(crate) fn module_name(&self) -> &SourceIndexModuleName {
        &self.module_name
    }

    #[cfg(test)]
    fn is_exact(&self) -> bool {
        self.status == SourceIndexProgressStatus::Complete
            && self.indexed_file_count == self.total_file_count
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SourceIndexSnapshotStamp {
    generation: SourceIndexGeneration,
    module_progress: BTreeSet<SourceIndexModuleProgress>,
    pending_count: SourceIndexPendingCount,
    progress_compatible: bool,
}

impl SourceIndexSnapshotStamp {
    pub(super) fn new(
        generation: SourceIndexGeneration,
        module_progress: BTreeSet<SourceIndexModuleProgress>,
        pending_count: SourceIndexPendingCount,
        progress_compatible: bool,
    ) -> Self {
        Self {
            generation,
            module_progress,
            pending_count,
            progress_compatible,
        }
    }

    pub(crate) fn module_progress(&self) -> &BTreeSet<SourceIndexModuleProgress> {
        &self.module_progress
    }

    pub(crate) fn pending_count(&self) -> SourceIndexPendingCount {
        self.pending_count
    }
}

impl SourceIndexSnapshotStamp {
    pub(crate) fn generation(&self) -> SourceIndexGeneration {
        self.generation
    }

    #[cfg(test)]
    pub(crate) fn is_exact(&self) -> bool {
        self.progress_compatible
            && !self.module_progress.is_empty()
            && self
                .module_progress
                .iter()
                .all(SourceIndexModuleProgress::is_exact)
            && self.pending_count.is_empty()
    }
}
