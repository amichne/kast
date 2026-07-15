pub fn install(args: InstallArgs) -> Result<InstallResult> {
    match args.command {
        InstallCommand::ActivateBundle(bundle_args) => {
            activate_bundle(bundle_args).map(InstallResult::ActivateBundle)
        }
        InstallCommand::Shell(shell_args) => install_shell(shell_args).map(InstallResult::Shell),
        InstallCommand::Completion(_) => Err(CliError::new(
            "CLI_USAGE",
            "`kast developer machine completion` must be handled as raw completion output",
        )),
    }
}
