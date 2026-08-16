use super::*;

const LAUNCHCTL: &str = "/bin/launchctl";

pub(super) fn registration_for(
    launch: &ServiceLaunchRegistration,
    directory: &Path,
) -> Result<ServiceManagerRegistration> {
    let domain = format!("gui/{}", launch.owner_uid);
    let id = launch.runtime_instance_id.simple();
    Ok(ServiceManagerRegistration::Launchd {
        domain,
        label: format!("io.github.amichne.kast.indexer.{id}"),
        definition_path: directory.join("launch.plist").display().to_string(),
    })
}

pub(super) fn render_definition(
    launch: &ServiceLaunchRegistration,
    manager: &ServiceManagerRegistration,
    launch_sha256: &str,
) -> Result<String> {
    let ServiceManagerRegistration::Launchd {
        label,
        definition_path,
        ..
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
    .map(|value| format!("    <string>{}</string>", xml_escape(value)))
    .collect::<Vec<_>>()
    .join("\n");
    Ok(format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n\
<plist version=\"1.0\">\n<dict>\n\
  <key>Label</key><string>{}</string>\n\
  <key>ProgramArguments</key>\n  <array>\n{}\n  </array>\n\
  <key>WorkingDirectory</key><string>{}</string>\n\
  <key>StandardOutPath</key><string>{}</string>\n\
  <key>StandardErrorPath</key><string>{}</string>\n\
  <key>RunAtLoad</key><false/>\n  <key>KeepAlive</key><false/>\n\
</dict>\n</plist>\n",
        xml_escape(label),
        arguments,
        xml_escape(&launch.working_directory),
        xml_escape(&launch.log_file),
        xml_escape(&launch.log_file),
    ))
}

pub(super) fn register(manager: &ServiceManagerRegistration) -> Result<()> {
    let ServiceManagerRegistration::Launchd {
        domain,
        definition_path,
        ..
    } = manager
    else {
        return Err(manager_platform_mismatch());
    };
    command_output(
        Command::new(LAUNCHCTL).args(["print", domain]),
        "access the launchd user domain",
    )?;
    command_output(
        Command::new(LAUNCHCTL).args(["bootstrap", domain, definition_path]),
        "register the launchd service",
    )?;
    Ok(())
}

pub(super) fn start(manager: &ServiceManagerRegistration) -> Result<ServiceManagerObservation> {
    let target = target(manager)?;
    let output = command_output(
        Command::new(LAUNCHCTL).args(["kickstart", "-p", &target]),
        "start the launchd service",
    )?;
    let pid = String::from_utf8_lossy(&output.stdout)
        .trim()
        .parse::<u64>()
        .map_err(|_| manager_error("launchctl kickstart did not return one decimal PID."))?;
    if pid == 0 {
        return Err(manager_error("launchctl kickstart returned PID zero."));
    }
    Ok(ServiceManagerObservation::Running(pid))
}

pub(super) fn inspect(manager: &ServiceManagerRegistration) -> Result<ServiceManagerObservation> {
    let target = target(manager)?;
    match Command::new(LAUNCHCTL).args(["print", &target]).status() {
        Ok(status) if status.success() => Ok(ServiceManagerObservation::Registered),
        Ok(_) => Ok(ServiceManagerObservation::Absent),
        Err(error) => Err(manager_error(&format!("Cannot inspect launchd service: {error}"))),
    }
}

pub(super) fn stop(manager: &ServiceManagerRegistration) -> Result<()> {
    let target = target(manager)?;
    command_output(
        Command::new(LAUNCHCTL).args(["bootout", &target]),
        "stop the launchd service",
    )?;
    Ok(())
}

pub(super) fn unregister(manager: &ServiceManagerRegistration) -> Result<()> {
    if inspect(manager)? == ServiceManagerObservation::Absent {
        Ok(())
    } else {
        stop(manager)
    }
}

fn target(manager: &ServiceManagerRegistration) -> Result<String> {
    let ServiceManagerRegistration::Launchd { domain, label, .. } = manager else {
        return Err(manager_platform_mismatch());
    };
    Ok(format!("{domain}/{label}"))
}

fn xml_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}
