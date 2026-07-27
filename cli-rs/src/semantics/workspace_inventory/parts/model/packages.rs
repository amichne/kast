#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct KotlinPackageFqName(String);

impl KotlinPackageFqName {
    pub(super) fn parse_persisted(value: String) -> Option<Self> {
        canonical_persisted_package_name(&value).then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

fn canonical_persisted_package_name(value: &str) -> bool {
    if value.is_empty() || value.trim() != value {
        return false;
    }
    let mut segment_length = 0usize;
    let mut in_backticks = false;
    let mut closed_backticks = false;
    for character in value.chars() {
        match character {
            '`' if segment_length == 0 && !in_backticks && !closed_backticks => {
                in_backticks = true;
            }
            '`' if in_backticks && segment_length > 0 => {
                in_backticks = false;
                closed_backticks = true;
            }
            '`' => return false,
            '.' if !in_backticks && segment_length > 0 => {
                segment_length = 0;
                closed_backticks = false;
            }
            '.' => return false,
            '/' | '\\' | '[' | ']' | ':' => return false,
            character if character.is_control() => return false,
            character if closed_backticks || (!in_backticks && character.is_whitespace()) => {
                return false;
            }
            _ => segment_length += 1,
        }
    }
    segment_length > 0 && !in_backticks
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspacePackageUnprovenReason {
    NotScanned,
    SemanticAnalysisUnavailable,
    SemanticAnalysisFailed,
    LegacyTextOnly,
}

impl WorkspacePackageUnprovenReason {
    pub(super) fn parse(value: &str) -> Option<Self> {
        match value {
            "NOT_SCANNED" => Some(Self::NotScanned),
            "SEMANTIC_ANALYSIS_UNAVAILABLE" => Some(Self::SemanticAnalysisUnavailable),
            "SEMANTIC_ANALYSIS_FAILED" => Some(Self::SemanticAnalysisFailed),
            "LEGACY_TEXT_ONLY" => Some(Self::LegacyTextOnly),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspacePackageInvalidReference {
    InvalidState,
    IllegalStateTuple,
    DanglingFqName,
    InvalidFqName,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum WorkspacePackageEvidence {
    ProvenRoot,
    ProvenNamed(KotlinPackageFqName),
    Unproven(WorkspacePackageUnprovenReason),
    Unavailable,
    InvalidReference(WorkspacePackageInvalidReference),
}
