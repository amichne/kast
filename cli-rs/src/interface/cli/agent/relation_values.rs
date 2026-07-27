#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CanonicalSymbolName(String);

impl CanonicalSymbolName {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for CanonicalSymbolName {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        validate_exact_name(value, "symbol")?;
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceDeclarationFile(String);

impl WorkspaceDeclarationFile {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for WorkspaceDeclarationFile {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        validate_exact_name(value, "declaration file")?;
        let path = std::path::Path::new(value);
        if !matches!(
            path.extension().and_then(|extension| extension.to_str()),
            Some("kt" | "kts")
        ) {
            return Err("declaration file must end in .kt or .kts".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DeclarationStartOffset(u32);

impl DeclarationStartOffset {
    pub(crate) fn get(self) -> u32 {
        self.0
    }
}

impl std::str::FromStr for DeclarationStartOffset {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        value
            .parse::<u32>()
            .map(Self)
            .map_err(|_| "declaration start offset must be a non-negative integer".to_string())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AgentRelationLimit(std::num::NonZeroU8);

impl AgentRelationLimit {
    pub(crate) fn get(self) -> u8 {
        self.0.get()
    }
}

impl Default for AgentRelationLimit {
    fn default() -> Self {
        Self(std::num::NonZeroU8::new(4).expect("relationship default limit is positive"))
    }
}

impl std::fmt::Display for AgentRelationLimit {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(formatter)
    }
}

impl std::str::FromStr for AgentRelationLimit {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let value = value
            .parse::<u16>()
            .map_err(|_| "relationship limit must be an integer from 1 through 200".to_string())?;
        if !(1..=200).contains(&value) {
            return Err("relationship limit must be from 1 through 200".to_string());
        }
        let value = u8::try_from(value)
            .map_err(|_| "relationship limit exceeded its typed range".to_string())?;
        Ok(Self(std::num::NonZeroU8::new(value).ok_or_else(|| {
            "relationship limit must be greater than 0".to_string()
        })?))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AgentRelationDepth(std::num::NonZeroU8);

impl AgentRelationDepth {
    pub(crate) fn get(self) -> u8 {
        self.0.get()
    }
}

impl Default for AgentRelationDepth {
    fn default() -> Self {
        Self(std::num::NonZeroU8::new(1).expect("relationship default depth is positive"))
    }
}

impl std::fmt::Display for AgentRelationDepth {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(formatter)
    }
}

impl std::str::FromStr for AgentRelationDepth {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let value = value
            .parse::<u8>()
            .map_err(|_| "relationship depth must be an integer from 1 through 8".to_string())?;
        if !(1..=8).contains(&value) {
            return Err("relationship depth must be from 1 through 8".to_string());
        }
        Ok(Self(std::num::NonZeroU8::new(value).ok_or_else(|| {
            "relationship depth must be greater than 0".to_string()
        })?))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentRelationPageToken(String);

impl AgentRelationPageToken {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for AgentRelationPageToken {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.len() > 4_096
            || !value.is_ascii()
            || value.chars().any(char::is_control)
            || !value.starts_with("krp1.")
        {
            return Err("relationship page token is malformed".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentSelectorHandle(String);

impl AgentSelectorHandle {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl Serialize for AgentSelectorHandle {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for AgentSelectorHandle {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        let value = String::deserialize(deserializer)?;
        value.parse().map_err(serde::de::Error::custom)
    }
}

impl std::str::FromStr for AgentSelectorHandle {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.len() > 4_096
            || !value.is_ascii()
            || value.chars().any(char::is_control)
            || !value.starts_with("ksh1.")
            || value.len() == "ksh1.".len()
        {
            return Err("selector handle is malformed".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentImpactPageToken(String);

impl AgentImpactPageToken {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for AgentImpactPageToken {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.len() > 256
            || !value.is_ascii()
            || value.chars().any(char::is_control)
            || !value.starts_with("kip1.")
        {
            return Err("impact page token is malformed".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AgentImpactDepth(std::num::NonZeroU8);

impl AgentImpactDepth {
    pub(crate) fn get(self) -> u8 {
        self.0.get()
    }
}

impl Default for AgentImpactDepth {
    fn default() -> Self {
        Self(std::num::NonZeroU8::new(3).expect("impact default depth is positive"))
    }
}

impl std::fmt::Display for AgentImpactDepth {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(formatter)
    }
}

impl std::str::FromStr for AgentImpactDepth {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let value = value
            .parse::<u8>()
            .map_err(|_| "impact depth must be an integer from 1 through 8".to_string())?;
        if !(1..=8).contains(&value) {
            return Err("impact depth must be from 1 through 8".to_string());
        }
        Ok(Self(std::num::NonZeroU8::new(value).ok_or_else(|| {
            "impact depth must be greater than 0".to_string()
        })?))
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AgentHierarchyDirection {
    Supertypes,
    Subtypes,
    Both,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AgentSymbolMode {
    #[default]
    Exact,
    Discovery,
}
