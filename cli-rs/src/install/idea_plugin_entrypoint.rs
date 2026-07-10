pub fn install_idea_plugin(
    args: IdeaPluginInstallArgs,
    reporter: &mut dyn InstallReporter,
) -> Result<InstallIdeaPluginResult> {
    let homebrew = run_reported_step(
        reporter,
        "Resolving Homebrew-installed Kast",
        |homebrew: &HomebrewContext| {
            format!(
                "Resolved Homebrew-installed Kast at {}",
                homebrew.cli_path.display()
            )
        },
        "Could not resolve a Homebrew-installed Kast",
        || {
            let homebrew = discover_homebrew_context()?;
            verify_homebrew_cli(&homebrew)?;
            Ok(homebrew)
        },
    )?;
    if !args.dry_run {
        run_reported_step(
            reporter,
            "Checking for running JetBrains IDEs",
            |_| "No running IntelliJ IDEA or Android Studio processes found".to_string(),
            "A JetBrains IDE is still running",
            require_jetbrains_ides_closed,
        )?;
    }
    let mut warnings = vec![];
    reporter.idea_plugin_step_started("Resolving Kast Homebrew tap")?;
    let formula_tap = match homebrew_formula_tap() {
        Ok(tap) => {
            reporter.idea_plugin_step_finished(&format!("Resolved Kast Homebrew tap {tap}"))?;
            tap
        }
        Err(error) => {
            warnings.push(format!(
                "Could not resolve the Homebrew tap for kast; using {DEFAULT_KAST_TAP}: {}",
                error.message
            ));
            reporter.idea_plugin_step_finished(&format!(
                "Using default Kast Homebrew tap {DEFAULT_KAST_TAP}"
            ))?;
            DEFAULT_KAST_TAP.to_string()
        }
    };
    let cask_token = args
        .cask_token
        .clone()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("{formula_tap}/{KAST_PLUGIN_CASK_NAME}"));
    install_idea_plugin_into_jetbrains_profiles(args, homebrew, cask_token, warnings, reporter)
}
