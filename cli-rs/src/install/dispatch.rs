pub fn install(args: InstallArgs, reporter: &mut dyn InstallReporter) -> Result<InstallResult> {
    match args.command {
        InstallCommand::ActivateBundle(bundle_args) => {
            activate_bundle(bundle_args).map(InstallResult::ActivateBundle)
        }
        InstallCommand::Skill(resource_args) => {
            install_skill(resource_args).map(InstallResult::Skill)
        }
        InstallCommand::Instructions(resource_args) => {
            install_instructions(resource_args).map(InstallResult::Instructions)
        }
        InstallCommand::Copilot(resource_args) => {
            install_copilot(resource_args).map(InstallResult::Copilot)
        }
        InstallCommand::Plugin(resource_args) => {
            install_idea_plugin(resource_args, reporter).map(InstallResult::IdeaPlugin)
        }
        InstallCommand::Shell(shell_args) => install_shell(shell_args).map(InstallResult::Shell),
        InstallCommand::Completion(_) => Err(CliError::new(
            "CLI_USAGE",
            "`kast developer machine completion` must be handled as raw completion output",
        )),
    }
}
