#[derive(Debug, Clone)]
pub(crate) struct FileFilter {
    file_glob: Option<String>,
    folder_filter: Option<String>,
    compiled_glob: Option<Pattern>,
}

impl FileFilter {
    pub(crate) fn new(file_glob: Option<String>, folder_filter: Option<String>) -> Result<Self> {
        let compiled_glob =
            match file_glob.as_deref() {
                None => None,
                Some(pattern) if pattern.starts_with("regex:") => {
                    return Err(CliError::new(
                        "METRICS_FILTER_UNSUPPORTED",
                        "regex: file filters are not supported by the Rust CLI metrics reader",
                    ));
                }
                Some(pattern) => {
                    let normalized = pattern.strip_prefix("glob:").unwrap_or(pattern);
                    Some(Pattern::new(normalized).map_err(|error| {
                        CliError::new("METRICS_FILTER_INVALID", error.to_string())
                    })?)
                }
            };
        Ok(Self {
            file_glob,
            folder_filter,
            compiled_glob,
        })
    }

    pub(crate) fn file_glob(&self) -> Option<&str> {
        self.file_glob.as_deref()
    }

    pub(crate) fn folder_filter(&self) -> Option<&str> {
        self.folder_filter.as_deref()
    }

    fn is_empty(&self) -> bool {
        self.file_glob.is_none() && self.folder_filter.is_none()
    }

    fn matches(&self, path: Option<&str>) -> bool {
        if self.is_empty() {
            return true;
        }
        let Some(path) = path else {
            return false;
        };
        if let Some(folder) = &self.folder_filter {
            let normalized = if folder.ends_with('/') {
                folder.clone()
            } else {
                format!("{folder}/")
            };
            if !path.starts_with(&normalized) {
                return false;
            }
        }
        if let Some(pattern) = &self.compiled_glob {
            return pattern.matches_path(Path::new(path));
        }
        true
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Confidence {
    level: String,
    index_completeness: f64,
    semantic_basis: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FanInMetric {
    target_fq_name: String,
    target_path: Option<String>,
    target_module_path: Option<String>,
    target_source_set: Option<String>,
    occurrence_count: i64,
    source_file_count: i64,
    source_module_count: i64,
    by_edge_kind: BTreeMap<String, i64>,
    confidence: Confidence,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FanOutMetric {
    source_path: String,
    source_module_path: Option<String>,
    source_source_set: Option<String>,
    occurrence_count: i64,
    target_symbol_count: i64,
    target_file_count: i64,
    target_module_count: i64,
    external_target_count: i64,
    by_edge_kind: BTreeMap<String, i64>,
    confidence: Confidence,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModuleCouplingMetric {
    source_module_path: String,
    source_source_set: Option<String>,
    target_module_path: String,
    target_source_set: Option<String>,
    reference_count: i64,
    public_api_count: i64,
    internal_leak_count: i64,
    by_edge_kind: BTreeMap<String, i64>,
    confidence: Confidence,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DeadCodeCandidate {
    fq_name: String,
    kind: String,
    visibility: String,
    path: Option<String>,
    module_path: Option<String>,
    source_set: Option<String>,
    confidence: Confidence,
    reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ChangeImpactNode {
    source_path: String,
    depth: usize,
    via_target_fq_name: String,
    edge_kind: Option<String>,
    occurrence_count: i64,
    confidence: Confidence,
}

const MAX_IMPACT_PAGE_OFFSET: usize = 10_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum ImpactSubjectKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
}

impl ImpactSubjectKind {
    fn as_index_kind(self) -> &'static str {
        match self {
            Self::Class => "CLASS",
            Self::Interface => "INTERFACE",
            Self::Object => "OBJECT",
            Self::Function => "FUNCTION",
            Self::Property => "PROPERTY",
        }
    }

    fn is_callable(self) -> bool {
        matches!(self, Self::Function | Self::Property)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ImpactSubjectIdentity {
    fq_name: String,
    declaration_file: PathBuf,
    declaration_start_offset: u64,
    kind: ImpactSubjectKind,
}

impl ImpactSubjectIdentity {
    pub(crate) fn new(
        fq_name: String,
        declaration_file: PathBuf,
        declaration_start_offset: u64,
        kind: ImpactSubjectKind,
    ) -> Self {
        Self {
            fq_name,
            declaration_file,
            declaration_start_offset,
            kind,
        }
    }

    pub(crate) fn fq_name(&self) -> &str {
        &self.fq_name
    }

    pub(crate) fn is_valid(&self) -> bool {
        !self.fq_name.trim().is_empty()
            && !self.declaration_file.as_os_str().is_empty()
            && self.declaration_file.extension().is_some_and(|extension| {
                matches!(extension.to_str(), Some("kt" | "kts"))
            })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct AgentImpactPageOffset(u16);

impl AgentImpactPageOffset {
    pub(crate) fn first() -> Self {
        Self(0)
    }

    pub(crate) fn get(self) -> usize {
        usize::from(self.0)
    }
}

impl TryFrom<usize> for AgentImpactPageOffset {
    type Error = String;

    fn try_from(value: usize) -> std::result::Result<Self, Self::Error> {
        if value > MAX_IMPACT_PAGE_OFFSET {
            return Err(format!(
                "impact page offset must be at most {MAX_IMPACT_PAGE_OFFSET}"
            ));
        }
        u16::try_from(value)
            .map(Self)
            .map_err(|_| "impact page offset exceeded its typed range".to_string())
    }
}

#[derive(Debug)]
pub(crate) struct BoundedMetricsResult {
    pub(crate) results: Value,
    pub(crate) total_count: usize,
    pub(crate) returned_count: usize,
    pub(crate) truncated: bool,
    pub(crate) next_offset: Option<AgentImpactPageOffset>,
}

#[derive(Debug)]
pub(crate) enum DirectMetricsError {
    Unavailable(String),
    Query(CliError),
}

impl DirectMetricsError {
    pub(crate) fn into_cli_error(self) -> CliError {
        match self {
            DirectMetricsError::Unavailable(message) => {
                CliError::new("METRICS_DB_UNAVAILABLE", message)
            }
            DirectMetricsError::Query(error) => error,
        }
    }
}

pub(crate) type DirectResult<T> = std::result::Result<T, DirectMetricsError>;

#[derive(Debug, Clone)]
pub(crate) struct MetricsQueryControls {
    cancel_flag: Option<Arc<AtomicBool>>,
    deadline: Option<Instant>,
    progress_budget: Option<usize>,
    progress_ops: c_int,
}

impl Default for MetricsQueryControls {
    fn default() -> Self {
        Self {
            cancel_flag: None,
            deadline: None,
            progress_budget: None,
            progress_ops: 10_000,
        }
    }
}

impl MetricsQueryControls {
    #[allow(dead_code)]
    pub(crate) fn with_cancel_flag(mut self, cancel_flag: Arc<AtomicBool>) -> Self {
        self.cancel_flag = Some(cancel_flag);
        self
    }

    #[allow(dead_code)]
    pub(crate) fn with_deadline(mut self, deadline: Instant) -> Self {
        self.deadline = Some(deadline);
        self
    }

    #[cfg(test)]
    fn for_test_progress_budget(progress_budget: usize) -> Self {
        Self {
            progress_budget: Some(progress_budget),
            progress_ops: 1,
            ..Self::default()
        }
    }

    fn needs_progress_handler(&self) -> bool {
        self.cancel_flag.is_some() || self.deadline.is_some() || self.progress_budget.is_some()
    }

    fn should_cancel(&self, remaining_budget: &mut Option<usize>) -> bool {
        if self
            .cancel_flag
            .as_ref()
            .is_some_and(|flag| flag.load(Ordering::Relaxed))
        {
            return true;
        }
        if self
            .deadline
            .is_some_and(|deadline| Instant::now() >= deadline)
        {
            return true;
        }
        if let Some(remaining) = remaining_budget {
            if *remaining == 0 {
                return true;
            }
            *remaining -= 1;
        }
        false
    }
}
