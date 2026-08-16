#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementSymbolKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
    Parameter,
    Unknown,
}

impl AgentReplacementSymbolKind {
    fn parse(value: &str) -> Option<Self> {
        match value {
            "CLASS" => Some(Self::Class),
            "INTERFACE" => Some(Self::Interface),
            "OBJECT" => Some(Self::Object),
            "FUNCTION" => Some(Self::Function),
            "PROPERTY" => Some(Self::Property),
            "PARAMETER" => Some(Self::Parameter),
            "UNKNOWN" => Some(Self::Unknown),
            _ => None,
        }
    }

    fn supports_replacement(self) -> bool {
        self == Self::Function
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactReplacementSymbolIdentity {
    fq_name: String,
    kind: AgentReplacementSymbolKind,
    declaration_file: String,
    declaration_start_offset: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
}

impl AgentExactReplacementSymbolIdentity {
    fn from_compiler(identity: &AgentCompilerSymbolIdentity) -> Option<Self> {
        let location = identity.fields.get("location")?.as_object()?;
        let target = Self {
            fq_name: identity.fq_name.clone(),
            kind: AgentReplacementSymbolKind::parse(identity.fields.get("kind")?.as_str()?)?,
            declaration_file: location.get("filePath")?.as_str()?.to_string(),
            declaration_start_offset: u32::try_from(location.get("startOffset")?.as_u64()?).ok()?,
            containing_type: match identity.fields.get("containingType") {
                None | Some(Value::Null) => None,
                Some(value) => Some(value.as_str()?.to_string()),
            },
        };
        target.is_valid().then_some(target)
    }

    fn from_relation(identity: &AgentRelationIdentityProjection) -> Option<Self> {
        let target = Self {
            fq_name: identity.fq_name.clone(),
            kind: AgentReplacementSymbolKind::parse(&identity.kind)?,
            declaration_file: identity.declaration_file.clone(),
            declaration_start_offset: u32::try_from(identity.declaration_start_offset).ok()?,
            containing_type: identity.containing_type.clone(),
        };
        target.is_valid().then_some(target)
    }

    fn is_valid(&self) -> bool {
        is_exact_replacement_name(&self.fq_name)
            && self.declaration_start_offset <= i32::MAX as u32
            && is_normalized_absolute_replacement_path(&self.declaration_file)
            && self
                .containing_type
                .as_ref()
                .is_none_or(|value| is_exact_replacement_name(value))
    }

    fn is_valid_replacement_target(&self) -> bool {
        self.is_valid() && self.kind.supports_replacement()
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(transparent)]
struct AgentReplacementSemanticGeneration(u64);

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentReplacementDeclarationScope {
    start_offset: u32,
    end_offset: u32,
    start_line: u32,
    end_line: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    source_text: Option<String>,
}

impl AgentReplacementDeclarationScope {
    fn is_valid(&self) -> bool {
        self.start_offset < self.end_offset
            && self.end_offset <= i32::MAX as u32
            && self.start_line > 0
            && self.start_line <= self.end_line
            && self.end_line <= i32::MAX as u32
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactReplacementLocation {
    file_path: String,
    start_offset: u32,
    end_offset: u32,
    start_line: u32,
    start_column: u32,
    preview: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    usage_site_scope: Option<AgentReplacementDeclarationScope>,
}

impl AgentExactReplacementLocation {
    fn is_valid(&self) -> bool {
        is_normalized_absolute_replacement_path(&self.file_path)
            && self.start_offset < self.end_offset
            && self.end_offset <= i32::MAX as u32
            && self.start_line > 0
            && self.start_line <= i32::MAX as u32
            && self.start_column > 0
            && self.start_column <= i32::MAX as u32
            && !self.preview.is_empty()
            && self
                .usage_site_scope
                .as_ref()
                .is_none_or(AgentReplacementDeclarationScope::is_valid)
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentReplacementFileHash {
    file_path: String,
    hash: String,
}

impl AgentReplacementFileHash {
    fn is_valid_for(&self, source_file: &str) -> bool {
        self.file_path == source_file
            && is_normalized_absolute_replacement_path(&self.file_path)
            && is_lowercase_sha256(&self.hash)
    }
}
