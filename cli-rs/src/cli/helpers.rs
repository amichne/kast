pub fn version() -> &'static str {
    option_env!("KAST_VERSION").unwrap_or(env!("CARGO_PKG_VERSION"))
}

pub fn release_revision() -> &'static str {
    env!("KAST_RELEASE_REVISION")
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CliBuildIdentity {
    #[serde(rename = "type")]
    pub identity_type: &'static str,
    pub version: &'static str,
    pub release_revision: &'static str,
    pub schema_version: u32,
}

pub fn build_identity() -> CliBuildIdentity {
    CliBuildIdentity {
        identity_type: "KAST_CLI_BUILD_IDENTITY",
        version: version(),
        release_revision: release_revision(),
        schema_version: 1,
    }
}

pub fn print_topic_help(topic: &[String]) -> crate::error::Result<()> {
    let mut command = Cli::command();
    let mut selected = &mut command;
    let mut traversed = Vec::new();
    for part in topic {
        traversed.push(part.as_str());
        let next = selected.find_subcommand_mut(part).ok_or_else(|| {
            crate::error::CliError::new(
                "CLI_HELP_TOPIC_NOT_FOUND",
                format!(
                    "No Kast help topic named `{}`. Run `kast --help` for the full command tree.",
                    traversed.join(" ")
                ),
            )
        })?;
        if next.is_hide_set() {
            return Err(crate::error::CliError::new(
                "CLI_HELP_TOPIC_NOT_FOUND",
                format!(
                    "No Kast help topic named `{}`. Run `kast --help` for the full command tree.",
                    traversed.join(" ")
                ),
            ));
        }
        selected = next;
    }
    selected.print_long_help()?;
    println!();
    Ok(())
}
