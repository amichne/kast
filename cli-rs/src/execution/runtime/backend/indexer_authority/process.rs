use super::*;
use std::ffi::{OsStr, OsString};
#[cfg(unix)]
use std::os::unix::ffi::OsStringExt as _;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct ManagedProcessIdentity {
    pub pid: u64,
    pub start_key: String,
    pub start_epoch_millis: u64,
    pub owner_uid: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct ObservedProcess {
    pub identity: ManagedProcessIdentity,
    pub command: Vec<OsString>,
}

impl ObservedProcess {
    pub(super) fn has_argument(&self, expected: &str) -> bool {
        self.command
            .iter()
            .any(|argument| argument == OsStr::new(expected))
    }

    pub(super) fn command_matches(&self, expected: &[String]) -> bool {
        self.command.len() == expected.len()
            && self
                .command
                .iter()
                .zip(expected)
                .all(|(observed, expected)| observed == OsStr::new(expected))
    }
}

pub(super) fn observe_process(pid: u64) -> Result<Option<ObservedProcess>> {
    if pid == 0 || pid > i32::MAX as u64 {
        return Ok(None);
    }
    #[cfg(target_os = "macos")]
    {
        observe_macos_process(pid)
    }
    #[cfg(target_os = "linux")]
    {
        observe_linux_process(pid)
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux")))]
    {
        Err(CliError::new(
            "RUNTIME_PROCESS_EVIDENCE_UNAVAILABLE",
            "Kast runtime process evidence is unsupported on this platform.",
        ))
    }
}

pub(super) fn observe_owned_process(pid: u64, owner_uid: u64) -> Result<Option<ObservedProcess>> {
    if pid == 0 || pid > i32::MAX as u64 {
        return Ok(None);
    }
    #[cfg(target_os = "macos")]
    let identity = macos_process_identity(pid)?;
    #[cfg(target_os = "linux")]
    let identity = linux_process_identity(pid)?;
    #[cfg(not(any(target_os = "macos", target_os = "linux")))]
    let identity: Option<ManagedProcessIdentity> = None;
    if identity.is_none_or(|identity| identity.owner_uid != owner_uid) {
        return Ok(None);
    }
    observe_process(pid)
}

pub(super) fn process_is_alive(pid: u64) -> Result<bool> {
    Ok(observe_process(pid)?.is_some())
}

include!("ownership/process_platform.rs");

fn process_io_error(pid: u64, evidence: &str, error: std::io::Error) -> CliError {
    CliError::new(
        "RUNTIME_PROCESS_EVIDENCE_UNAVAILABLE",
        format!("Cannot read {evidence} for runtime PID {pid}: {error}"),
    )
}

fn process_error(message: &str) -> CliError {
    CliError::new("RUNTIME_PROCESS_EVIDENCE_UNAVAILABLE", message)
}
