
    #[test]
    fn workspace_up_reports_how_idea_was_reached() {
        assert_eq!(
            serde_json::to_value(LaunchDisposition::ReusedOpenProject).unwrap(),
            "REUSED_OPEN_PROJECT"
        );
        assert_eq!(
            serde_json::to_value(LaunchDisposition::OpenedInRunningIdea).unwrap(),
            "OPENED_IN_RUNNING_IDEA"
        );
        assert_eq!(
            serde_json::to_value(LaunchDisposition::LaunchedIdea).unwrap(),
            "LAUNCHED_IDEA"
        );
    }

    #[test]
    fn headless_workspace_reuse_omits_idea_launch_disposition() {
        let selected = candidate("headless", RuntimeState::Ready, false);

        assert_eq!(reused_project_launch_disposition(&selected), None);
    }

    #[test]
    fn running_idea_host_selection_reuses_one_process_and_rejects_multiple() {
        let mut first = candidate("idea", RuntimeState::Ready, false).descriptor;
        first.workspace_root = "/work/first".to_string();
        first.pid = 41;
        let mut same_process = first.clone();
        same_process.workspace_root = "/work/second".to_string();
        let mut other_process = first.clone();
        other_process.workspace_root = "/work/third".to_string();
        other_process.pid = 42;

        assert_eq!(
            select_running_idea_host(&[first.clone(), same_process])
                .unwrap()
                .unwrap()
                .pid,
            41
        );
        let error = select_running_idea_host(&[first, other_process])
            .expect_err("more than one IDEA process must be explicit");
        assert_eq!(error.code, "IDEA_HOST_AMBIGUOUS");
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn explicit_app_selects_only_its_running_idea_process() {
        let mut idea = candidate("idea", RuntimeState::Ready, false).descriptor;
        idea.workspace_root = "/work/idea".to_string();
        idea.pid = 41;
        let mut android = idea.clone();
        android.workspace_root = "/work/android".to_string();
        android.pid = 42;
        let selected_app = Path::new("/Applications/Android Studio.app");

        let selected = select_running_idea_host_for_app(
            &[idea, android],
            selected_app,
            |pid| match pid {
                41 => Some(PathBuf::from("/Applications/IntelliJ IDEA.app")),
                42 => Some(selected_app.to_path_buf()),
                _ => None,
            },
        )
        .unwrap()
        .expect("the explicit Android Studio process");

        assert_eq!(selected.pid, 42);
        assert_eq!(
            idea_app_bundle_for_executable(Path::new(
                "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java",
            )),
            Some(selected_app.to_path_buf()),
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn sidecar_host_selection_prefers_owner_then_configured_then_sole_install() {
        let temp = tempfile::tempdir().unwrap();
        let workspace = temp.path().join("workspace");
        std::fs::create_dir(&workspace).unwrap();
        let owner = supported_idea_test_app(temp.path(), "Owner.app");
        let configured = supported_idea_test_app(temp.path(), "Configured.app");
        let sole = supported_idea_test_app(temp.path(), "Sole.app");
        let mut descriptor = candidate("idea", RuntimeState::Ready, false).descriptor;
        descriptor.workspace_root = workspace.display().to_string();
        descriptor.pid = 41;

        let selected = select_macos_idea_app_for_workspace(
            &workspace,
            std::slice::from_ref(&descriptor),
            &configured,
            vec![sole.clone()],
            |pid| (pid == 41).then(|| owner.clone()),
        )
        .unwrap();
        assert_eq!(selected, owner);

        let selected = select_macos_idea_app_for_workspace(
            &workspace,
            &[],
            &configured,
            vec![sole.clone()],
            |_| None,
        )
        .unwrap();
        assert_eq!(selected, std::fs::canonicalize(&configured).unwrap());

        let selected = select_macos_idea_app_for_workspace(
            &workspace,
            &[],
            Path::new("idea"),
            vec![sole.clone()],
            |_| None,
        )
        .unwrap();
        assert_eq!(selected, sole);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn sidecar_host_selection_reports_typed_install_ambiguity() {
        let temp = tempfile::tempdir().unwrap();
        let workspace = temp.path().join("workspace");
        std::fs::create_dir(&workspace).unwrap();
        let idea = supported_idea_test_app(temp.path(), "IntelliJ IDEA.app");
        let android = supported_android_studio_test_app(temp.path(), "Android Studio.app");

        let error = select_macos_idea_app_for_workspace(
            &workspace,
            &[],
            Path::new("idea"),
            vec![idea, android],
            |_| None,
        )
        .expect_err("multiple compatible installs require an explicit host");

        assert_eq!(error.code, "IDEA_HOST_AMBIGUOUS");
    }

    #[cfg(target_os = "macos")]
    fn supported_idea_test_app(root: &Path, name: &str) -> PathBuf {
        supported_test_app(root, name, "IU-262.1")
    }

    #[cfg(target_os = "macos")]
    fn supported_android_studio_test_app(root: &Path, name: &str) -> PathBuf {
        supported_test_app(root, name, "AI-261.1")
    }

    #[cfg(target_os = "macos")]
    fn supported_test_app(root: &Path, name: &str, build: &str) -> PathBuf {
        let app = root.join(name);
        std::fs::create_dir_all(app.join("Contents/Resources")).unwrap();
        std::fs::write(app.join("Contents/Resources/build.txt"), build).unwrap();
        app
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn running_idea_host_without_compatible_metadata_is_rejected() {
        let mut host = candidate("idea", RuntimeState::Ready, false).descriptor;
        host.backend_version = "stale".to_string();

        let error = require_compatible_running_idea_plugin(&host, &KastConfig::defaults())
            .expect_err("an unverified loaded plugin must be rejected before project opening");

        assert_eq!(error.code, "IDEA_PLUGIN_UPDATE_REQUIRED");
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn open_project_request_is_private_exact_root_and_one_shot() {
        use std::os::unix::fs::PermissionsExt;

        let temp = tempfile::tempdir().unwrap();
        let workspace = temp.path().join("workspace");
        std::fs::create_dir(&workspace).unwrap();

        let request =
            write_open_project_request(temp.path(), &workspace, Some(41), None).unwrap();
        let payload: IdeaOpenProjectRequest =
            serde_json::from_slice(&std::fs::read(&request.path).unwrap()).unwrap();

        assert_eq!(payload.canonical_root, std::fs::canonicalize(workspace).unwrap());
        assert_eq!(payload.request_id, request.request_id);
        assert_eq!(payload.target_pid, Some(41));
        assert_eq!(payload.target_product_code, None);
        assert_eq!(
            std::fs::metadata(&request.path).unwrap().permissions().mode() & 0o777,
            0o600
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn idea_autolaunch_defers_plugin_metadata_until_bootstrap() {
        let temp = tempfile::tempdir().unwrap();
        std::fs::write(temp.path().join("settings.gradle.kts"), "").unwrap();
        let mut config = KastConfig::defaults();
        config.runtime.idea_launch.enabled = true;

        assert!(should_defer_macos_workspace_validation(
            temp.path(),
            RuntimeBackendPreference::Fixed(BackendName::Idea),
            &config,
        ));
        assert!(should_defer_macos_workspace_validation(
            temp.path(),
            RuntimeBackendPreference::Fixed(BackendName::Idea),
            &config,
        ));
        config.runtime.idea_launch.enabled = false;
        assert!(!should_defer_macos_workspace_validation(
            temp.path(),
            RuntimeBackendPreference::Fixed(BackendName::Idea),
            &config,
        ));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn non_gradle_workspace_is_rejected_before_idea_launch() {
        let temp = tempfile::tempdir().unwrap();

        let error = validate_macos_idea_gradle_workspace(
            temp.path(),
            RuntimeBackendPreference::Fixed(BackendName::Idea),
        )
        .expect_err("IDEA launch must not defer validation for a non-Gradle root");

        assert_eq!(error.code, "SEMANTIC_WORKSPACE_UNSUPPORTED");
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn supported_macos_hosts_are_idea_262_and_android_studio_261() {
        let temp = tempfile::tempdir().unwrap();
        let idea = temp.path().join("IntelliJ IDEA.app");
        let android = temp.path().join("Android Studio.app");
        let old_idea = temp.path().join("IntelliJ IDEA 261.app");
        for app in [&idea, &android, &old_idea] {
            std::fs::create_dir_all(app.join("Contents/Resources")).unwrap();
        }
        std::fs::write(idea.join("Contents/Resources/build.txt"), "IU-262.8665.258").unwrap();
        std::fs::write(
            android.join("Contents/Resources/build.txt"),
            "AI-261.25134.95.2612.15822958",
        )
        .unwrap();
        std::fs::write(
            old_idea.join("Contents/Resources/build.txt"),
            "IU-261.25134.95",
        )
        .unwrap();

        assert!(is_supported_idea_app(&idea));
        assert!(is_supported_idea_app(&android));
        assert!(!is_supported_idea_app(&old_idea));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_app_and_profile_selection_covers_toolbox_custom_and_ambiguous_installs() {
        let temp = tempfile::tempdir().unwrap();
        let home = temp.path().join("home");
        let toolbox = home.join(
            "Library/Application Support/JetBrains/Toolbox/apps/IDEA-U/ch-0/262.1/IntelliJ IDEA.app",
        );
        let android = temp.path().join("Custom Android Studio.app");
        for (app, build, product_info) in [
            (
                &toolbox,
                "IU-262.8665.258",
                r#"{"productCode":"IU","dataDirectoryName":"IntelliJIdea2026.2"}"#,
            ),
            (
                &android,
                "AI-261.25134.95.2612.15822958",
                r#"{"productCode":"AI","dataDirectoryName":"AndroidStudio2026.1"}"#,
            ),
        ] {
            std::fs::create_dir_all(app.join("Contents/Resources")).unwrap();
            std::fs::write(app.join("Contents/Resources/build.txt"), build).unwrap();
            std::fs::write(
                app.join("Contents/Resources/product-info.json"),
                product_info,
            )
            .unwrap();
        }

        let discovered = idea_apps_in(
            &home.join("Library/Application Support/JetBrains/Toolbox/apps"),
            7,
        );
        assert_eq!(
            select_supported_idea_app(discovered).unwrap(),
            toolbox,
        );
        assert_eq!(
            idea_plugin_directory_for_app(&toolbox, &home).unwrap(),
            home.join(
                "Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast",
            ),
        );
        assert_eq!(
            idea_plugin_directory_for_app(&android, &home).unwrap(),
            home.join(
                "Library/Application Support/Google/AndroidStudio2026.1/plugins/kast",
            ),
        );
        let ambiguous = select_supported_idea_app(vec![toolbox, android])
            .expect_err("two supported bundles must be explicit");
        assert_eq!(ambiguous.code, "IDEA_HOST_AMBIGUOUS");
    }

    include!("idea_lifecycle/runtime_compatibility.rs");
