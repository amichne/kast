use super::registration_invalid;
use crate::error::Result;
use std::path::Path;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct ServiceSocketPath(String);

impl ServiceSocketPath {
    pub(super) fn from_command(command: &[String]) -> Result<Self> {
        let mut paths = command
            .iter()
            .filter_map(|argument| argument.strip_prefix("--socket-path="));
        let path = paths
            .next()
            .ok_or_else(|| registration_invalid("Indexer service command has no socket path."))?;
        if path.is_empty() || !Path::new(path).is_absolute() || paths.next().is_some() {
            return Err(registration_invalid(
                "Indexer service command socket path is invalid or duplicated.",
            ));
        }
        Ok(Self(path.to_string()))
    }

    pub(super) fn into_string(self) -> String {
        self.0
    }

    pub(super) fn require_matches(&self, persisted: &str) -> Result<()> {
        if self.0 == persisted {
            Ok(())
        } else {
            Err(registration_invalid(
                "Service registration socket path does not match its command.",
            ))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requested_socket_path_is_extracted_from_service_command_review_regression() {
        let command = vec![
            "/usr/bin/java".to_string(),
            "io.github.amichne.kast.indexer.KastIndexerMainKt".to_string(),
            "--socket-path=/tmp/requested-kast.sock".to_string(),
        ];

        let socket = ServiceSocketPath::from_command(&command).expect("service socket path");

        assert_eq!(socket.into_string(), "/tmp/requested-kast.sock");
    }

    #[test]
    fn service_socket_path_rejects_missing_relative_or_duplicate_arguments() {
        for command in [
            vec!["java".to_string()],
            vec![
                "java".to_string(),
                "--socket-path=relative.sock".to_string(),
            ],
            vec![
                "java".to_string(),
                "--socket-path=/tmp/first.sock".to_string(),
                "--socket-path=/tmp/second.sock".to_string(),
            ],
        ] {
            assert!(ServiceSocketPath::from_command(&command).is_err());
        }
    }

    #[test]
    fn persisted_socket_mismatch_is_rejected_review_regression() {
        let command = vec![
            "java".to_string(),
            "--socket-path=/tmp/requested.sock".to_string(),
        ];
        let socket = ServiceSocketPath::from_command(&command).expect("service socket path");

        assert!(socket.require_matches("/tmp/default.sock").is_err());
    }
}
