#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAdditionSymbolKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
    Parameter,
    Unknown,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionSymbolIdentity {
    fq_name: String,
    kind: AgentAdditionSymbolKind,
    declaration_file: String,
    declaration_start_offset: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
}

impl AgentAdditionSymbolIdentity {
    fn validate(&self) -> std::result::Result<(), String> {
        if self.fq_name.trim().is_empty()
            || self.kind == AgentAdditionSymbolKind::Unknown
            || !is_normalized_absolute_exact_file_path(&self.declaration_file)
            || self.declaration_start_offset > i32::MAX as u32
            || self
                .containing_type
                .as_ref()
                .is_some_and(|value| value.trim().is_empty())
        {
            return Err("addition proof contained an invalid compiler symbol identity".to_string());
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(tag = "type", deny_unknown_fields)]
enum AgentAdditionKotlinPackage {
    #[serde(rename = "ROOT")]
    Root,
    #[serde(rename = "NAMED")]
    Named { segments: Vec<String> },
}

impl AgentAdditionKotlinPackage {
    fn validate(&self) -> std::result::Result<(), String> {
        match self {
            Self::Root => Ok(()),
            Self::Named { segments }
                if !segments.is_empty()
                    && segments.iter().all(|segment| {
                        !segment.is_empty() && !segment.chars().any(char::is_control)
                    }) =>
            {
                Ok(())
            }
            Self::Named { .. } => {
                Err("addition proof contained an invalid Kotlin package".to_string())
            }
        }
    }

    fn collision_key(&self) -> String {
        match self {
            Self::Root => "ROOT".to_string(),
            Self::Named { segments } => format!("NAMED:{}", segments.join("\u{0}")),
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionSourceOwner {
    source_root: String,
    idea_module_name: String,
    gradle_build_root: String,
    gradle_project_path: String,
    source_set_name: String,
}

impl AgentAdditionSourceOwner {
    fn validate_for(&self, target_path: &str) -> std::result::Result<(), String> {
        if !is_normalized_absolute_exact_file_path(&self.source_root)
            || !is_normalized_absolute_exact_file_path(&self.gradle_build_root)
            || !strict_descendant(&self.source_root, &self.gradle_build_root)
            || !strict_descendant(target_path, &self.source_root)
            || !is_canonical_nonblank(&self.idea_module_name)
            || !is_valid_gradle_project_path(&self.gradle_project_path)
            || !is_canonical_nonblank(&self.source_set_name)
            || self.source_set_name.contains(['/', '\\', ':'])
        {
            return Err("addition proof contained invalid source ownership".to_string());
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAdditionDeclarationKind {
    Class,
    Interface,
    Object,
    EnumClass,
    AnnotationClass,
    Function,
    Property,
    TypeAlias,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionRelativeRange {
    start_offset: u32,
    end_offset: u32,
}

impl AgentAdditionRelativeRange {
    fn validate(&self) -> bool {
        self.start_offset < self.end_offset && self.end_offset <= i32::MAX as u32
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionDeclaration {
    package_identity: AgentAdditionKotlinPackage,
    name: String,
    kind: AgentAdditionDeclarationKind,
    relative_range: AgentAdditionRelativeRange,
    collision_signature: String,
}

impl AgentAdditionDeclaration {
    fn validate_for(
        &self,
        package_identity: &AgentAdditionKotlinPackage,
        content_length: usize,
    ) -> std::result::Result<(), String> {
        if &self.package_identity != package_identity
            || self.name.is_empty()
            || self.name.chars().any(char::is_control)
            || !self.relative_range.validate()
            || self.relative_range.end_offset as usize > content_length
            || !is_lowercase_exact_file_sha256(&self.collision_signature)
        {
            return Err("addition proof contained an invalid top-level declaration".to_string());
        }
        self.package_identity.validate()
    }

    fn collision_key(&self) -> String {
        let category = match self.kind {
            AgentAdditionDeclarationKind::Class
            | AgentAdditionDeclarationKind::Interface
            | AgentAdditionDeclarationKind::Object
            | AgentAdditionDeclarationKind::EnumClass
            | AgentAdditionDeclarationKind::AnnotationClass
            | AgentAdditionDeclarationKind::TypeAlias => "CLASSIFIER".to_string(),
            AgentAdditionDeclarationKind::Function => {
                format!("FUNCTION:{}", self.collision_signature)
            }
            AgentAdditionDeclarationKind::Property => {
                format!("PROPERTY:{}", self.collision_signature)
            }
        };
        format!(
            "{}\u{0}{}\u{0}{category}",
            self.package_identity.collision_key(),
            self.name
        )
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase", deny_unknown_fields)]
enum AgentAdditionResolvedTarget {
    #[serde(rename = "SOURCE")]
    Source {
        identity: AgentAdditionSymbolIdentity,
    },
    #[serde(rename = "EXTERNAL")]
    External {
        fq_name: String,
        kind: AgentAdditionSymbolKind,
        compiler_signature: String,
    },
}

impl AgentAdditionResolvedTarget {
    fn validate(&self) -> std::result::Result<(), String> {
        match self {
            Self::Source { identity } => identity.validate(),
            Self::External {
                fq_name,
                kind,
                compiler_signature,
            } if is_canonical_nonblank(fq_name)
                && *kind != AgentAdditionSymbolKind::Unknown
                && is_canonical_nonblank(compiler_signature) =>
            {
                Ok(())
            }
            Self::External { .. } => {
                Err("addition proof contained an invalid external compiler target".to_string())
            }
        }
    }

    fn source_file_path(&self) -> Option<&str> {
        match self {
            Self::Source { identity } => Some(&identity.declaration_file),
            Self::External { .. } => None,
        }
    }
}
