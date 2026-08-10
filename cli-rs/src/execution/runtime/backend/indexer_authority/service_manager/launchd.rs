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
        "__runtime-service-entrypoint",
        "--registration",
        registration
            .to_str()
            .ok_or_else(manager_platform_mismatch)?,
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
    command_output(
        Command::new(LAUNCHCTL).args(["kickstart", "-p", &target]),
        "start the launchd service",
    )?;
    inspect(manager)
}

pub(super) fn inspect(manager: &ServiceManagerRegistration) -> Result<ServiceManagerObservation> {
    let target = target(manager)?;
    let output = Command::new(LAUNCHCTL)
        .args(["print", &target])
        .output()
        .map_err(|error| manager_error(&format!("Cannot inspect launchd service: {error}")))?;
    if output.status.success() {
        return parse_launchd_observation(&String::from_utf8_lossy(&output.stdout));
    }
    let stderr = String::from_utf8_lossy(&output.stderr);
    if output.status.code() == Some(113) && stderr.contains("Could not find service") {
        Ok(ServiceManagerObservation::Absent)
    } else {
        Err(manager_error(&format!(
            "Cannot inspect launchd service: {}",
            stderr.trim()
        )))
    }
}

fn parse_launchd_observation(output: &str) -> Result<ServiceManagerObservation> {
    let mut pid = None;
    for line in output.lines().map(str::trim) {
        let Some(value) = line.strip_prefix("pid =") else {
            continue;
        };
        if pid.is_some() {
            return Err(manager_error(
                "launchctl print returned more than one service PID.",
            ));
        }
        let parsed = value
            .trim()
            .parse::<u64>()
            .map_err(|_| manager_error("launchctl print returned a malformed service PID."))?;
        if parsed == 0 {
            return Err(manager_error("launchctl print returned service PID zero."));
        }
        pid = Some(parsed);
    }
    Ok(pid.map_or(
        ServiceManagerObservation::Registered,
        ServiceManagerObservation::Running,
    ))
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

#[cfg(test)]
mod tests {
    use super::*;

    struct RegisteredLaunchdService(ServiceManagerRegistration);

    impl Drop for RegisteredLaunchdService {
        fn drop(&mut self) {
            let _ = unregister(&self.0);
        }
    }

    #[test]
    fn launchd_print_reports_one_exact_pid() {
        assert_eq!(
            parse_launchd_observation("noise\n    pid = 4821\nmore noise\n").unwrap(),
            ServiceManagerObservation::Running(4821),
        );
        assert_eq!(
            parse_launchd_observation("state = exited\n").unwrap(),
            ServiceManagerObservation::Registered,
        );
    }

    #[test]
    fn launchd_print_rejects_duplicate_or_malformed_pid() {
        for output in ["pid = 1\npid = 2\n", "pid = nope\n", "pid = 0\n"] {
            assert!(parse_launchd_observation(output).is_err(), "{output:?}");
        }
    }

    #[test]
    fn launchd_owns_runtime_after_start_call_returns() {
        use std::os::unix::fs::PermissionsExt;

        let temp = tempfile::tempdir().expect("launchd fixture");
        let launcher = temp.path().join("kastctl-fixture");
        fs::write(&launcher, "#!/bin/sh\nwhile :; do sleep 1; done\n").expect("fixture launcher");
        let mut permissions = fs::metadata(&launcher)
            .expect("launcher metadata")
            .permissions();
        permissions.set_mode(0o700);
        fs::set_permissions(&launcher, permissions).expect("executable launcher");
        let launch = ServiceLaunchRegistration {
            schema_version: 1,
            workspace_root: temp.path().display().to_string(),
            workspace_key: "fixture".to_string(),
            runtime_instance_id: uuid::Uuid::new_v4(),
            owner_uid: u64::from(unsafe { libc::geteuid() }),
            working_directory: temp.path().display().to_string(),
            command: vec![launcher.display().to_string()],
            environment: serde_json::from_value(serde_json::json!({})).expect("environment"),
            log_file: temp.path().join("service.log").display().to_string(),
            descriptor_directory: temp.path().join("descriptors").display().to_string(),
            socket_path: temp.path().join("runtime.sock").display().to_string(),
            launcher_path: launcher.display().to_string(),
            launcher_sha256: "0".repeat(64),
            installed_release: None,
            runtime_config_path: temp.path().join("runtime.json").display().to_string(),
            runtime_config_sha256: "0".repeat(64),
        };
        let manager = registration_for(&launch, temp.path()).expect("launchd registration");
        let definition = render_definition(&launch, &manager, "0").expect("launchd definition");
        fs::write(manager.definition_path(), definition).expect("launchd plist");

        register(&manager).expect("register launchd fixture");
        let cleanup = RegisteredLaunchdService(manager.clone());
        let started = start(&manager).expect("start launchd fixture");
        let ServiceManagerObservation::Running(started_pid) = started else {
            panic!("launchd did not report a running service: {started:?}");
        };
        std::thread::sleep(std::time::Duration::from_millis(100));

        assert_eq!(
            inspect(&manager).expect("inspect launchd fixture"),
            ServiceManagerObservation::Running(started_pid),
        );
        drop(cleanup);
    }
}
