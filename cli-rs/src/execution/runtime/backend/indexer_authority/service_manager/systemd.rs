use super::*;
use std::collections::BTreeMap;

const SYSTEMCTL: &str = "/usr/bin/systemctl";

pub(super) fn registration_for(
    launch: &ServiceLaunchRegistration,
    directory: &Path,
) -> Result<ServiceManagerRegistration> {
    let unit = format!(
        "kast-indexer-{}.service",
        launch.runtime_instance_id.simple()
    );
    Ok(ServiceManagerRegistration::SystemdUser {
        definition_path: directory.join(&unit).display().to_string(),
        unit,
    })
}

pub(super) fn render_definition(
    launch: &ServiceLaunchRegistration,
    manager: &ServiceManagerRegistration,
    launch_sha256: &str,
) -> Result<String> {
    let ServiceManagerRegistration::SystemdUser {
        definition_path, ..
    } = manager
    else {
        return Err(manager_platform_mismatch());
    };
    let registration = Path::new(definition_path)
        .parent()
        .ok_or_else(manager_platform_mismatch)?
        .join("launch.json");
    let arguments = [
        launch.launcher_path.as_str(),
        "developer",
        "runtime",
        "service-entrypoint",
        "--registration",
        registration
            .to_str()
            .ok_or_else(manager_platform_mismatch)?,
        "--registration-sha256",
        launch_sha256,
    ]
    .into_iter()
    .map(systemd_argument)
    .collect::<Result<Vec<_>>>()?
    .join(" ");
    Ok(format!(
        "[Unit]\nDescription=Kast indexer {}\n\n[Service]\nType=exec\nExecStart=:{arguments}\nWorkingDirectory={}\nRestart=no\nKillMode=control-group\nTimeoutStopSec=10s\n",
        launch.runtime_instance_id,
        systemd_argument(&launch.working_directory)?,
    ))
}

pub(super) fn register(manager: &ServiceManagerRegistration) -> Result<()> {
    register_with(manager, &mut SystemctlCommand)
}

trait SystemctlRunner {
    fn output(&mut self, arguments: &[&str]) -> std::io::Result<std::process::Output>;
}

struct SystemctlCommand;

impl SystemctlRunner for SystemctlCommand {
    fn output(&mut self, arguments: &[&str]) -> std::io::Result<std::process::Output> {
        Command::new(SYSTEMCTL).args(arguments).output()
    }
}

fn register_with(
    manager: &ServiceManagerRegistration,
    runner: &mut impl SystemctlRunner,
) -> Result<()> {
    let (unit, definition_path) = values(manager)?;
    checked_systemctl(
        runner,
        &["--user", "--no-pager", "show-environment"],
        "access the systemd user manager",
    )?;
    checked_systemctl(
        runner,
        &["--user", "--no-pager", "link", definition_path],
        "register the systemd user service",
    )?;
    if let Err(reload_error) = checked_systemctl(
        runner,
        &["--user", "--no-pager", "daemon-reload"],
        "reload systemd user services",
    ) {
        rollback_partial_registration(runner, unit)?;
        return Err(reload_error);
    }
    Ok(())
}

fn checked_systemctl(
    runner: &mut impl SystemctlRunner,
    arguments: &[&str],
    action: &str,
) -> Result<std::process::Output> {
    let output = runner.output(arguments).map_err(|error| {
        manager_error(&format!("Runtime service manager cannot {action}: {error}"))
    })?;
    if output.status.success() {
        Ok(output)
    } else {
        Err(manager_error(&format!(
            "Runtime service manager cannot {action}: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        )))
    }
}

fn rollback_partial_registration(runner: &mut impl SystemctlRunner, unit: &str) -> Result<()> {
    let disable = runner.output(&["--user", "--no-pager", "disable", unit]);
    let reload = runner.output(&["--user", "--no-pager", "daemon-reload"]);
    if disable.is_ok_and(|output| output.status.success())
        && reload.is_ok_and(|output| output.status.success())
    {
        Ok(())
    } else {
        Err(CliError::new(
            "RUNTIME_SERVICE_REGISTRATION_AMBIGUOUS",
            "A partial systemd registration could not be removed and reloaded.",
        ))
    }
}

pub(super) fn start(manager: &ServiceManagerRegistration) -> Result<ServiceManagerObservation> {
    let (unit, _) = values(manager)?;
    command_output(
        Command::new(SYSTEMCTL).args(["--user", "--no-pager", "start", unit]),
        "start the systemd user service",
    )?;
    inspect(manager)
}

pub(super) fn inspect(manager: &ServiceManagerRegistration) -> Result<ServiceManagerObservation> {
    inspect_with(manager, &mut SystemctlCommand)
}

fn inspect_with(
    manager: &ServiceManagerRegistration,
    runner: &mut impl SystemctlRunner,
) -> Result<ServiceManagerObservation> {
    let (unit, definition_path) = values(manager)?;
    let output = runner
        .output(&[
            "--user",
            "--no-pager",
            "--property=LoadState",
            "--property=ActiveState",
            "--property=SubState",
            "--property=MainPID",
            "--property=FragmentPath",
            "show",
            unit,
        ])
        .map_err(|error| manager_error(&format!("Cannot inspect systemd user service: {error}")))?;
    let properties = parse_properties(&String::from_utf8_lossy(&output.stdout))?;
    let observation = classify_properties(&properties)?;
    if observation == ServiceManagerObservation::Absent {
        return Ok(observation);
    }
    if !output.status.success() {
        return Err(manager_error(&format!(
            "Cannot inspect systemd user service: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        )));
    }
    if Path::new(&properties["FragmentPath"]) != Path::new(definition_path) {
        return Err(manager_error(
            "systemd service definition path does not match registration.",
        ));
    }
    Ok(observation)
}

pub(super) fn unregister(manager: &ServiceManagerRegistration) -> Result<()> {
    unregister_with(manager, &mut SystemctlCommand)
}

fn unregister_with(
    manager: &ServiceManagerRegistration,
    runner: &mut impl SystemctlRunner,
) -> Result<()> {
    let (unit, _) = values(manager)?;
    match inspect_with(manager, runner)? {
        ServiceManagerObservation::Absent => return Ok(()),
        ServiceManagerObservation::Registered => {}
        ServiceManagerObservation::Running(_) => {
            return Err(manager_error(
                "Cannot disable a running systemd user service.",
            ));
        }
    }
    checked_systemctl(
        runner,
        &["--user", "--no-pager", "disable", unit],
        "disable the systemd user service",
    )?;
    if inspect_with(manager, runner)? == ServiceManagerObservation::Absent {
        Ok(())
    } else {
        Err(manager_error(&format!(
            "systemd user service {unit} remained registered after disable."
        )))
    }
}

fn classify_properties(properties: &BTreeMap<String, String>) -> Result<ServiceManagerObservation> {
    let pid = properties["MainPID"]
        .parse::<u64>()
        .map_err(|_| manager_error("systemd MainPID is not decimal."))?;
    if properties["LoadState"] == "not-found" {
        return if properties["ActiveState"] == "inactive"
            && properties["SubState"] == "dead"
            && pid == 0
            && properties["FragmentPath"].is_empty()
        {
            Ok(ServiceManagerObservation::Absent)
        } else {
            Err(manager_error(
                "systemd reports contradictory evidence for an absent service.",
            ))
        };
    }
    if properties["LoadState"] != "loaded" {
        return Err(manager_error(
            "systemd service has an unexpected load state.",
        ));
    }
    match (
        properties["ActiveState"].as_str(),
        properties["SubState"].as_str(),
        pid,
    ) {
        ("active", "running", pid) if pid > 0 => Ok(ServiceManagerObservation::Running(pid)),
        ("inactive", "dead", 0) | ("failed", "failed", 0) => {
            Ok(ServiceManagerObservation::Registered)
        }
        _ => Err(manager_error(
            "systemd service state and MainPID are contradictory or unsupported.",
        )),
    }
}

fn parse_properties(output: &str) -> Result<BTreeMap<String, String>> {
    let expected = [
        "LoadState",
        "ActiveState",
        "SubState",
        "MainPID",
        "FragmentPath",
    ];
    let mut properties = BTreeMap::new();
    for line in output.lines().filter(|line| !line.is_empty()) {
        let (name, value) = line
            .split_once('=')
            .ok_or_else(|| manager_error("systemd show returned a malformed property."))?;
        if !expected.contains(&name)
            || properties
                .insert(name.to_string(), value.to_string())
                .is_some()
        {
            return Err(manager_error(
                "systemd show returned unexpected or duplicate properties.",
            ));
        }
    }
    if expected.iter().any(|name| !properties.contains_key(*name)) {
        return Err(manager_error("systemd show omitted a required property."));
    }
    Ok(properties)
}

fn values(manager: &ServiceManagerRegistration) -> Result<(&str, &str)> {
    let ServiceManagerRegistration::SystemdUser {
        unit,
        definition_path,
    } = manager
    else {
        return Err(manager_platform_mismatch());
    };
    Ok((unit, definition_path))
}

fn systemd_argument(value: &str) -> Result<String> {
    if value
        .chars()
        .any(|value| matches!(value, '\0' | '\n' | '\r'))
    {
        return Err(manager_error(
            "systemd service arguments cannot contain control separators.",
        ));
    }
    Ok(format!(
        "\"{}\"",
        value
            .replace('\\', "\\\\")
            .replace('"', "\\\"")
            .replace('%', "%%")
    ))
}

#[cfg(test)]
#[path = "systemd_tests.rs"]
mod tests;
