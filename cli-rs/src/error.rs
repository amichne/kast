use crate::SCHEMA_VERSION;
use serde::Serialize;
use std::collections::BTreeMap;
use std::io;
use thiserror::Error;

pub type Result<T> = std::result::Result<T, CliError>;

#[derive(Debug, Error)]
#[error("{message}")]
pub struct CliError {
    pub code: &'static str,
    pub message: String,
    pub details: BTreeMap<String, String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CliErrorResponse {
    pub code: &'static str,
    pub message: String,
    #[serde(skip_serializing_if = "BTreeMap::is_empty")]
    pub details: BTreeMap<String, String>,
    pub schema_version: u32,
}

impl CliError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            details: BTreeMap::new(),
        }
    }

    pub fn to_response(&self) -> CliErrorResponse {
        CliErrorResponse {
            code: self.code,
            message: self.message.clone(),
            details: self.details.clone(),
            schema_version: SCHEMA_VERSION,
        }
    }

    pub fn from_clap(error: clap::Error) -> Self {
        let code = if error.use_stderr() {
            "CLI_USAGE"
        } else {
            "CLI_HELP"
        };
        Self::new(code, error.to_string())
    }
}

impl From<io::Error> for CliError {
    fn from(value: io::Error) -> Self {
        Self::new("IO_ERROR", value.to_string())
    }
}

impl From<serde_json::Error> for CliError {
    fn from(value: serde_json::Error) -> Self {
        Self::new("JSON_ERROR", value.to_string())
    }
}

impl From<toml::de::Error> for CliError {
    fn from(value: toml::de::Error) -> Self {
        Self::new("CONFIG_ERROR", value.to_string())
    }
}

impl From<toml::ser::Error> for CliError {
    fn from(value: toml::ser::Error) -> Self {
        Self::new("CONFIG_ERROR", value.to_string())
    }
}
