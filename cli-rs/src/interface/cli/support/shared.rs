#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum BackendName {
    Indexer,
}

impl BackendName {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Indexer => "indexer",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::BackendName;

    #[test]
    fn active_backend_identity_rejects_the_retired_idea_selector() {
        assert!(serde_json::from_str::<BackendName>("\"idea\"").is_err());
    }
}
