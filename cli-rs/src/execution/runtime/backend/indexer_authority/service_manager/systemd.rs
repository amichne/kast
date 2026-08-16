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
        registration.to_str().ok_or_else(manager_platform_mismatch)?,
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
    let (_, definition_path) = values(manager)?;
    command_output(
        Command::new(SYSTEMCTL).args(["--user", "--no-pager", "show-environment"]),
        "access the systemd user manager",
    )?;
    command_output(
        Command::new(SYSTEMCTL).args(["--user", "--no-pager", "link", definition_path]),
        "register the systemd user service",
    )?;
    command_output(
        Command::new(SYSTEMCTL).args(["--user", "--no-pager", "daemon-reload"]),
        "reload systemd user services",
    )?;
    Ok(())
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
    let (unit, definition_path) = values(manager)?;
    let output = command_output(
        Command::new(SYSTEMCTL).args([
            "--user",
            "--no-pager",
            "--property=LoadState",
            "--property=ActiveState",
            "--property=SubState",
            "--property=MainPID",
            "--property=FragmentPath",
            "show",
            unit,
        ]),
        "inspect the systemd user service",
    )?;
    let properties = parse_properties(&String::from_utf8_lossy(&output.stdout))?;
    if properties["LoadState"] == "not-found" {
        return Ok(ServiceManagerObservation::Absent);
    }
    if Path::new(&properties["FragmentPath"]) != Path::new(definition_path) {
        return Err(manager_error("systemd service definition path does not match registration."));
    }
    let pid = properties["MainPID"]
        .parse::<u64>()
        .map_err(|_| manager_error("systemd MainPID is not decimal."))?;
    if properties["ActiveState"] == "active" && pid > 0 {
        Ok(ServiceManagerObservation::Running(pid))
    } else {
        Ok(ServiceManagerObservation::Registered)
    }
}

pub(super) fn stop(manager: &ServiceManagerRegistration) -> Result<()> {
    let (unit, _) = values(manager)?;
    command_output(
        Command::new(SYSTEMCTL).args(["--user", "--no-pager", "stop", unit]),
        "stop the systemd user service",
    )?;
    Ok(())
}

pub(super) fn unregister(manager: &ServiceManagerRegistration) -> Result<()> {
    let (unit, _) = values(manager)?;
    let _ = Command::new(SYSTEMCTL)
        .args(["--user", "--no-pager", "disable", unit])
        .status();
    command_output(
        Command::new(SYSTEMCTL).args(["--user", "--no-pager", "daemon-reload"]),
        "reload systemd user services",
    )?;
    Ok(())
}

fn parse_properties(output: &str) -> Result<BTreeMap<String, String>> {
    let expected = ["LoadState", "ActiveState", "SubState", "MainPID", "FragmentPath"];
    let mut properties = BTreeMap::new();
    for line in output.lines().filter(|line| !line.is_empty()) {
        let (name, value) = line
            .split_once('=')
            .ok_or_else(|| manager_error("systemd show returned a malformed property."))?;
        if !expected.contains(&name) || properties.insert(name.to_string(), value.to_string()).is_some() {
            return Err(manager_error("systemd show returned unexpected or duplicate properties."));
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
    if value.chars().any(|value| matches!(value, '\0' | '\n' | '\r')) {
        return Err(manager_error("systemd service arguments cannot contain control separators."));
    }
    Ok(format!(
        "\"{}\"",
        value
            .replace('\\', "\\\\")
            .replace('"', "\\\"")
            .replace('%', "%%")
    ))
}
