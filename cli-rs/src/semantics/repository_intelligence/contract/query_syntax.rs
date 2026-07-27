#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
enum RepositoryQuerySyntax {
    #[default]
    NaturalLanguage,
    Regex,
}

enum RepositoryDiscoveryQuery {
    NaturalLanguage(String),
    Regex {
        source: String,
        compiled: regex::Regex,
    },
}

impl RepositoryDiscoveryQuery {
    fn parse(source: String, syntax: RepositoryQuerySyntax) -> Result<Self> {
        if source.trim().is_empty() {
            return Err(invalid_repository_query(
                "repository question must not be blank",
            ));
        }
        match syntax {
            RepositoryQuerySyntax::NaturalLanguage => Ok(Self::NaturalLanguage(source)),
            RepositoryQuerySyntax::Regex => {
                let compiled = regex::Regex::new(&source)
                    .map_err(|error| invalid_repository_regex(error.to_string()))?;
                Ok(Self::Regex { source, compiled })
            }
        }
    }

    fn as_str(&self) -> &str {
        match self {
            Self::NaturalLanguage(question) => question,
            Self::Regex { source, .. } => source,
        }
    }

    fn natural_language(&self) -> Option<&str> {
        match self {
            Self::NaturalLanguage(question) => Some(question),
            Self::Regex { .. } => None,
        }
    }

    fn syntax_canonical(&self) -> &'static str {
        match self {
            Self::NaturalLanguage(_) => "NATURAL_LANGUAGE",
            Self::Regex { .. } => "REGEX",
        }
    }

    fn discovery_canonical(&self) -> &'static str {
        match self {
            Self::NaturalLanguage(_) => "LEXICAL",
            Self::Regex { .. } => "REGEX",
        }
    }

    fn candidate_lookup(&self) -> &'static str {
        match self {
            Self::NaturalLanguage(_) => "deterministic compiler-symbol ranking",
            Self::Regex { .. } => "retrieval-only regex over compiler-symbol documents",
        }
    }
}

fn invalid_repository_regex(cause: String) -> CliError {
    let mut error = CliError::new(
        "INVALID_REPOSITORY_REGEX",
        "repository question is not a valid Rust regex",
    );
    error
        .details
        .insert("field".to_string(), "question".to_string());
    error.details.insert(
        "remedy".to_string(),
        "Use a valid Rust regex in question, set querySyntax=natural_language, or pass --query-syntax natural-language."
            .to_string(),
    );
    error.details.insert("cause".to_string(), cause);
    error
}
