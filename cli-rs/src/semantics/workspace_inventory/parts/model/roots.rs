#[derive(Debug, Error)]
pub(crate) enum WorkspaceRootError {
    #[error("workspace root `{path}` cannot be canonicalized: {source}")]
    Canonicalize {
        path: PathBuf,
        source: std::io::Error,
    },
    #[error("workspace root `{0}` is not a directory")]
    NotDirectory(PathBuf),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct WorkspaceRoot(PathBuf);

impl WorkspaceRoot {
    pub(crate) fn as_path(&self) -> &Path {
        &self.0
    }
}

impl TryFrom<&Path> for WorkspaceRoot {
    type Error = WorkspaceRootError;

    fn try_from(path: &Path) -> Result<Self, Self::Error> {
        let canonical =
            std::fs::canonicalize(path).map_err(|source| WorkspaceRootError::Canonicalize {
                path: path.to_path_buf(),
                source,
            })?;
        if !canonical.is_dir() {
            return Err(WorkspaceRootError::NotDirectory(canonical));
        }
        Ok(Self(canonical))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct WorkspaceFilePath(PathBuf);

impl WorkspaceFilePath {
    pub(super) fn from_relative_path(path: PathBuf) -> Option<Self> {
        let mut saw_component = false;
        for component in path.components() {
            match component {
                Component::Normal(_) => saw_component = true,
                Component::CurDir if !saw_component => {}
                Component::CurDir
                | Component::ParentDir
                | Component::RootDir
                | Component::Prefix(_) => return None,
            }
        }
        saw_component.then_some(Self(path))
    }

    pub(crate) fn as_path(&self) -> &Path {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct WorkspaceContainedRoot(PathBuf);

impl WorkspaceContainedRoot {
    pub(super) fn from_relative_path(path: PathBuf) -> Option<Self> {
        if path.as_os_str().is_empty() || path == Path::new(".") {
            return Some(Self(PathBuf::new()));
        }
        path.components()
            .all(|component| matches!(component, Component::Normal(_)))
            .then_some(Self(path))
    }

    pub(crate) fn as_path(&self) -> &Path {
        &self.0
    }
}

impl fmt::Display for WorkspaceFilePath {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.display().fmt(formatter)
    }
}
