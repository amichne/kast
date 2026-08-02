#[derive(Debug)]
pub(crate) enum WorkspaceCollectionPatternError {
    Empty,
    ControlCharacter,
    Comment,
    Negation,
    ParentTraversal,
    FilesystemAbsolute,
    Malformed(glob::PatternError),
}

impl fmt::Display for WorkspaceCollectionPatternError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Empty => write!(formatter, "pattern must not be empty"),
            Self::ControlCharacter => {
                write!(formatter, "pattern must not contain control characters")
            }
            Self::Comment => write!(formatter, "comments are not supported"),
            Self::Negation => write!(formatter, "negation is not supported"),
            Self::ParentTraversal => write!(formatter, "parent traversal is not supported"),
            Self::FilesystemAbsolute => {
                write!(formatter, "pattern must be repository-relative")
            }
            Self::Malformed(error) => write!(formatter, "malformed pattern: {error}"),
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct WorkspaceCollectionPattern {
    matcher: glob::Pattern,
    basename_only: bool,
}

impl WorkspaceCollectionPattern {
    pub(crate) fn parse(
        raw: &str,
    ) -> std::result::Result<Self, WorkspaceCollectionPatternError> {
        let raw = raw.strip_prefix('\u{feff}').unwrap_or(raw);
        if raw.trim().is_empty() {
            return Err(WorkspaceCollectionPatternError::Empty);
        }
        if raw.chars().any(char::is_control) {
            return Err(WorkspaceCollectionPatternError::ControlCharacter);
        }
        if raw.starts_with('#') {
            return Err(WorkspaceCollectionPatternError::Comment);
        }
        if raw.starts_with('!') {
            return Err(WorkspaceCollectionPatternError::Negation);
        }

        let separator_normalized = raw.replace('\\', "/");
        let segments = separator_normalized
            .trim_start_matches('/')
            .split('/')
            .filter(|segment| !segment.is_empty())
            .collect::<Vec<_>>();
        if segments.contains(&"..") {
            return Err(WorkspaceCollectionPatternError::ParentTraversal);
        }
        let starts_at_filesystem_root = separator_normalized.starts_with("//")
            || separator_normalized
                .as_bytes()
                .get(1)
                .is_some_and(|byte| *byte == b':')
            || (separator_normalized.starts_with('/')
                && segments.len() > 2
                && segments.first().is_some_and(|segment| {
                    matches!(
                        segment.to_ascii_lowercase().as_str(),
                        "users"
                            | "home"
                            | "volumes"
                            | "private"
                            | "var"
                            | "tmp"
                            | "opt"
                            | "usr"
                            | "etc"
                    )
                }));
        if starts_at_filesystem_root {
            return Err(WorkspaceCollectionPatternError::FilesystemAbsolute);
        }

        let normalized = raw.trim_start_matches('/').trim_end_matches('/');
        if normalized.is_empty() {
            return Err(WorkspaceCollectionPatternError::Empty);
        }
        let matcher = glob::Pattern::new(normalized)
            .map_err(WorkspaceCollectionPatternError::Malformed)?;
        Ok(Self {
            matcher,
            basename_only: !normalized.contains('/'),
        })
    }

    pub(crate) fn matches(&self, repository_relative_path: &str) -> bool {
        let path = Path::new(repository_relative_path);
        if self.basename_only {
            return path.components().any(|component| {
                component
                    .as_os_str()
                    .to_str()
                    .is_some_and(|value| self.matcher.matches(value))
            });
        }
        let options = glob::MatchOptions {
            case_sensitive: true,
            require_literal_separator: true,
            require_literal_leading_dot: false,
        };
        path.ancestors()
            .any(|candidate| self.matcher.matches_path_with(candidate, options))
    }
}
