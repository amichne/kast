#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct WorkspaceRelativeGradleBuildRoot(PathBuf);

impl WorkspaceRelativeGradleBuildRoot {
    pub(super) fn parse(value: String) -> Option<Self> {
        if value == "." {
            return Some(Self(PathBuf::new()));
        }
        if value.is_empty()
            || value.starts_with('/')
            || value.contains('\\')
            || value.chars().any(char::is_control)
            || has_windows_drive_prefix(&value)
        {
            return None;
        }
        let segments: Vec<_> = value.split('/').collect();
        if segments
            .iter()
            .any(|segment| segment.is_empty() || matches!(*segment, "." | ".."))
        {
            return None;
        }
        Some(Self(segments.iter().collect()))
    }

    pub(crate) fn as_path(&self) -> &Path {
        &self.0
    }
}

fn has_windows_drive_prefix(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':'
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct GradleProjectPath(String);

impl GradleProjectPath {
    pub(super) fn parse(value: String) -> Option<Self> {
        if !value.starts_with(':')
            || value.contains(['/', '\\', '#'])
            || value.chars().any(char::is_control)
            || (value != ":"
                && value
                    .split(':')
                    .skip(1)
                    .any(|segment| segment.is_empty() || segment.trim() != segment))
        {
            return None;
        }
        Some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct BuildQualifiedGradleProjectIdentity {
    build_root: WorkspaceRelativeGradleBuildRoot,
    project_path: GradleProjectPath,
}

impl BuildQualifiedGradleProjectIdentity {
    pub(crate) fn parse(build_root: String, project_path: String) -> Option<Self> {
        Some(Self {
            build_root: WorkspaceRelativeGradleBuildRoot::parse(build_root)?,
            project_path: GradleProjectPath::parse(project_path)?,
        })
    }

    pub(crate) fn build_root(&self) -> &WorkspaceRelativeGradleBuildRoot {
        &self.build_root
    }

    pub(crate) fn project_path(&self) -> &GradleProjectPath {
        &self.project_path
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct GradleSourceSetName(String);

impl GradleSourceSetName {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty()
            && value.trim() == value
            && !value.contains(['/', '\\', ':', '#'])
            && !value.chars().any(char::is_control))
        .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct BuildQualifiedGradleSourceSetIdentity {
    project: BuildQualifiedGradleProjectIdentity,
    source_set_name: GradleSourceSetName,
}

impl BuildQualifiedGradleSourceSetIdentity {
    pub(crate) fn parse(
        build_root: String,
        project_path: String,
        source_set_name: String,
    ) -> Option<Self> {
        Some(Self {
            project: BuildQualifiedGradleProjectIdentity::parse(build_root, project_path)?,
            source_set_name: GradleSourceSetName::parse(source_set_name)?,
        })
    }

    pub(crate) fn project(&self) -> &BuildQualifiedGradleProjectIdentity {
        &self.project
    }

    pub(crate) fn source_set_name(&self) -> &GradleSourceSetName {
        &self.source_set_name
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct LegacySourceSetLabel(String);

impl LegacySourceSetLabel {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty() && value.trim() == value && !value.chars().any(char::is_control))
            .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum WorkspaceSourceSetEvidence {
    Proven(BTreeSet<BuildQualifiedGradleSourceSetIdentity>),
    Unproven(BTreeSet<LegacySourceSetLabel>),
    Unavailable,
}
