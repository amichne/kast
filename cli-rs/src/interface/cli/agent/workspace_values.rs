#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WorkspaceFileLimit(std::num::NonZeroU8);

impl WorkspaceFileLimit {
    pub(crate) fn get(self) -> u8 {
        self.0.get()
    }
}

impl Default for WorkspaceFileLimit {
    fn default() -> Self {
        Self(std::num::NonZeroU8::new(20).expect("workspace-file default limit is positive"))
    }
}

impl std::fmt::Display for WorkspaceFileLimit {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(formatter)
    }
}

impl std::str::FromStr for WorkspaceFileLimit {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let value = value.parse::<u16>().map_err(|_| {
            "workspace-file limit must be an integer from 1 through 200".to_string()
        })?;
        if !(1..=200).contains(&value) {
            return Err("workspace-file limit must be from 1 through 200".to_string());
        }
        let value = u8::try_from(value)
            .map_err(|_| "workspace-file limit exceeded its typed range".to_string())?;
        Ok(Self(std::num::NonZeroU8::new(value).ok_or_else(|| {
            "workspace-file limit must be greater than 0".to_string()
        })?))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceFilesPublicPageToken(uuid::Uuid);

impl WorkspaceFilesPublicPageToken {
    pub(crate) fn canonical(&self) -> String {
        self.0.hyphenated().to_string()
    }
}

impl std::str::FromStr for WorkspaceFilesPublicPageToken {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let parsed = uuid::Uuid::parse_str(value)
            .map_err(|_| "workspace-file page token must be a canonical UUID v4".to_string())?;
        if parsed.get_version() != Some(uuid::Version::Random)
            || parsed.hyphenated().to_string() != value
        {
            return Err("workspace-file page token must be a canonical UUID v4".to_string());
        }
        Ok(Self(parsed))
    }
}

fn validate_exact_name(value: &str, label: &str) -> Result<(), String> {
    if invalid_exact_name(value) {
        return Err(format!(
            "{label} must be non-blank without control characters"
        ));
    }
    Ok(())
}

fn invalid_exact_name(value: &str) -> bool {
    value.is_empty() || value.trim() != value || value.chars().any(char::is_control)
}

fn normalize_workspace_relative_path(value: &str, label: &str) -> Result<String, String> {
    if value.is_empty()
        || value.trim() != value
        || value.contains('\\')
        || is_platform_qualified_path(value)
    {
        return Err(format!(
            "{label} must be a non-blank workspace-relative forward-slash path"
        ));
    }
    let mut segments = Vec::new();
    for component in std::path::Path::new(value).components() {
        match component {
            std::path::Component::Normal(segment) => segments.push(
                segment
                    .to_str()
                    .ok_or_else(|| format!("{label} must be valid UTF-8"))?,
            ),
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                return Err(format!("{label} cannot escape the workspace with `..`"));
            }
            std::path::Component::RootDir | std::path::Component::Prefix(_) => {
                return Err(format!("{label} must be workspace-relative"));
            }
        }
    }
    if segments.is_empty() {
        return Err(format!("{label} must name a workspace-relative path"));
    }
    Ok(segments.join("/"))
}

fn is_platform_qualified_path(value: &str) -> bool {
    let bytes = value.as_bytes();
    value.starts_with(['/', '\\'])
        || matches!(
            bytes,
            [drive, b':', ..] if drive.is_ascii_alphabetic()
        )
}

fn canonical_kotlin_package_segments(value: &str) -> Result<Vec<String>, String> {
    if value.is_empty() || value.trim() != value {
        return Err("named package selectors require a non-blank package name".to_string());
    }
    let mut segments = Vec::new();
    let mut segment = String::new();
    let mut quoted = false;
    let mut in_backticks = false;
    for character in value.chars() {
        match character {
            '`' if segment.is_empty() && !in_backticks => {
                quoted = true;
                in_backticks = true;
            }
            '`' if in_backticks => in_backticks = false,
            '.' if !in_backticks => {
                segments.push(canonical_kotlin_package_segment(&segment, quoted)?);
                segment.clear();
                quoted = false;
            }
            _ if quoted && !in_backticks => {
                return Err("backticked package segments must end before `.`".to_string());
            }
            _ => segment.push(character),
        }
    }
    if in_backticks {
        return Err("backticked package segments must have a closing backtick".to_string());
    }
    segments.push(canonical_kotlin_package_segment(&segment, quoted)?);
    Ok(segments)
}

fn canonical_kotlin_package_segment(value: &str, quoted: bool) -> Result<String, String> {
    if value.is_empty() {
        return Err("Kotlin package names cannot contain empty segments".to_string());
    }
    if quoted {
        if value.chars().any(|character| {
            character.is_control() || matches!(character, '.' | '/' | '\\' | '[' | ']' | ':')
        }) {
            return Err("backticked package segments contain an invalid character".to_string());
        }
        return Ok(value.to_string());
    }
    if !is_plain_kotlin_identifier(value) {
        return Err(format!(
            "`{value}` is not a canonical Kotlin package segment; use backticks for escaped names"
        ));
    }
    Ok(value.to_string())
}

fn is_plain_kotlin_identifier(value: &str) -> bool {
    plain_kotlin_identifier_validator().is_valid(&serde_json::Value::String(value.to_string()))
        && !is_kotlin_hard_keyword(value)
}

fn plain_kotlin_identifier_validator() -> &'static jsonschema::Validator {
    static VALIDATOR: std::sync::OnceLock<jsonschema::Validator> = std::sync::OnceLock::new();
    VALIDATOR.get_or_init(|| {
        let schema = serde_json::json!({
            "type": "string",
            "pattern": r"^(?:_|\p{L})(?:_|\p{L}|\p{Nd})*$"
        });
        jsonschema::options()
            .with_pattern_options(jsonschema::PatternOptions::regex())
            .build(&schema)
            .expect("the static Kotlin package identifier schema is valid")
    })
}

fn is_kotlin_hard_keyword(value: &str) -> bool {
    matches!(
        value,
        "as" | "break"
            | "class"
            | "continue"
            | "do"
            | "else"
            | "false"
            | "for"
            | "fun"
            | "if"
            | "in"
            | "interface"
            | "is"
            | "null"
            | "object"
            | "package"
            | "return"
            | "super"
            | "this"
            | "throw"
            | "true"
            | "try"
            | "typealias"
            | "typeof"
            | "val"
            | "var"
            | "when"
            | "while"
    )
}
