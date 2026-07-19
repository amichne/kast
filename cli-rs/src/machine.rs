use serde::Serialize;
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum MachineState {
    NotInstalled,
    Installed,
}

impl fmt::Display for MachineState {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::NotInstalled => "not installed",
            Self::Installed => "installed",
        })
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MachineStatus {
    #[serde(rename = "type")]
    status_type: &'static str,
    pub(crate) state: MachineState,
    schema_version: u32,
}

pub(crate) fn status() -> MachineStatus {
    let root = crate::config::home_dir().join("Library/Application Support/Kast/machine");
    let installed = root.join("current").exists();
    MachineStatus {
        status_type: "KAST_MACHINE_STATUS",
        state: if installed {
            MachineState::Installed
        } else {
            MachineState::NotInstalled
        },
        schema_version: 1,
    }
}
