pub(crate) fn service_entrypoint(args: RuntimeServiceEntrypointArgs) -> Result<()> {
    use std::os::unix::process::CommandExt as _;
    use std::process::Stdio;

    let _install_use_lock = super::registration::storage::InstallUseLock::acquire()?;
    let registration =
        validate_entrypoint_registration(&args.registration, &args.registration_sha256)?;
    let current_executable = fs::canonicalize(std::env::current_exe()?)?;
    if current_executable != fs::canonicalize(&registration.launch.launcher_path)?
        || super::registration::storage::sha256_stable_file(&current_executable, false)?
            != registration.launch.launcher_sha256
    {
        return Err(CliError::new(
            "RUNTIME_REGISTRATION_INVALID",
            "Service entrypoint executable does not match the immutable registration.",
        ));
    }
    let current = observe_process(u64::from(std::process::id()))?.ok_or_else(|| {
        CliError::new(
            "RUNTIME_PROCESS_EVIDENCE_UNAVAILABLE",
            "Service entrypoint cannot observe its own process identity.",
        )
    })?;
    write_process_claim(
        &registration.directory,
        &registration.receipt.launch_sha256,
        current.identity,
    )?;
    let executable = registration.launch.command.first().ok_or_else(|| {
        CliError::new("RUNTIME_REGISTRATION_INVALID", "Indexer command is empty.")
    })?;
    let error = Command::new(executable)
        .args(&registration.launch.command[1..])
        .current_dir(&registration.launch.working_directory)
        .env_clear()
        .envs(&registration.launch.environment)
        .stdin(Stdio::null())
        .exec();
    Err(CliError::new(
        "RUNTIME_SERVICE_EXEC_FAILED",
        format!("Runtime service could not execute the registered indexer: {error}"),
    ))
}
