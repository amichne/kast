pub fn activate_bundle(args: ActivateBundleArgs) -> Result<ActivateBundleResult> {
    let source = config::normalize(args.source.clone());
    let scratch = ScratchDir::new("kast-activate-bundle")?;
    let bundle_root = bundle_source_root(&source, scratch.path())?;
    let bundle = validate_bundle(&bundle_root)?;
    let targets = activation_target_paths(&args, &bundle)?;

    if args.verify_only {
        verify_activated_bundle(&bundle, &targets)?;
        return Ok(activate_bundle_result(&bundle, &targets, true, true));
    }

    if verify_activated_bundle(&bundle, &targets).is_ok() {
        return Ok(activate_bundle_result(&bundle, &targets, true, false));
    }

    install_validated_bundle(&bundle, &targets)?;
    verify_activated_bundle(&bundle, &targets)?;
    Ok(activate_bundle_result(&bundle, &targets, false, false))
}
