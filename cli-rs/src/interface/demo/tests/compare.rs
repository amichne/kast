    #[test]
    fn compare_filters_are_single_select_and_filter_semantic_rows() {
        let rows = vec![
            sample_compare_row(
                Some("app.PublicThing"),
                "PublicThing",
                "CLASS",
                "PUBLIC",
                ":app",
                "main",
                ["CALL"],
            ),
            sample_compare_row(
                Some("lib.PrivateHelper"),
                "PrivateHelper",
                "FUNCTION",
                "PRIVATE",
                ":lib",
                "test",
                ["TYPE_REF"],
            ),
        ];
        let filters = CompareFilters {
            kind: Some("FUNCTION".to_string()),
            visibility: Some("PRIVATE".to_string()),
            source_set: Some("test".to_string()),
            module: Some(":lib".to_string()),
            relation: Some("TYPE_REF".to_string()),
        };

        let filtered = apply_compare_filters(&rows, &filters);

        assert_eq!(filtered.len(), 1);
        assert_eq!(filtered[0].fq_name.as_deref(), Some("lib.PrivateHelper"));
    }

    #[test]
    fn compare_diff_buckets_separate_lexical_noise_semantic_only_and_filtered_rows() {
        let lexical = vec![
            sample_compare_row(
                Some("lib.Foo"),
                "Foo",
                "CLASS",
                "PUBLIC",
                ":lib",
                "main",
                ["CALL"],
            ),
            sample_lexical_only_row("FooNotes"),
        ];
        let semantic = vec![
            sample_compare_row(
                Some("lib.Foo"),
                "Foo",
                "CLASS",
                "PUBLIC",
                ":lib",
                "main",
                ["CALL"],
            ),
            sample_compare_row(
                Some("lib.FooWidget"),
                "FooWidget",
                "CLASS",
                "PUBLIC",
                ":lib",
                "main",
                ["CALL"],
            ),
        ];
        let filtered = vec![semantic[0].clone()];

        let buckets = build_compare_diff_buckets(&lexical, &semantic, &filtered);

        assert_eq!(buckets.common_count, 1);
        assert_eq!(buckets.lexical_only[0].label, "FooNotes");
        assert_eq!(
            buckets.semantic_only[0].fq_name.as_deref(),
            Some("lib.FooWidget")
        );
        assert!(
            buckets.filtered_out.is_empty(),
            "semantic-only rows should not also be counted as filtered-out rows"
        );
    }

    #[test]
    fn compare_diff_buckets_keep_common_filtered_rows_separate() {
        let lexical = vec![sample_compare_row(
            Some("lib.Foo"),
            "Foo",
            "CLASS",
            "PUBLIC",
            ":lib",
            "main",
            ["CALL"],
        )];
        let semantic = vec![lexical[0].clone()];
        let filtered = Vec::new();

        let buckets = build_compare_diff_buckets(&lexical, &semantic, &filtered);

        assert_eq!(buckets.common_count, 1);
        assert!(buckets.lexical_only.is_empty());
        assert!(buckets.semantic_only.is_empty());
        assert_eq!(buckets.filtered_out[0].fq_name.as_deref(), Some("lib.Foo"));
    }

    #[test]
    fn compare_selection_prefers_the_active_lexical_pane() {
        let lexical = vec![sample_lexical_only_row("FooNotes")];
        let semantic = vec![sample_compare_row(
            Some("lib.Foo"),
            "Foo",
            "CLASS",
            "PUBLIC",
            ":lib",
            "main",
            ["CALL"],
        )];

        let selected = selected_compare_row(None, &lexical, &semantic, 0, 0, ComparePane::Lexical)
            .expect("selected row");

        assert_eq!(selected.0, ComparePane::Lexical);
        assert_eq!(selected.2.label, "FooNotes");
    }

    #[test]
    fn compare_module_sort_renders_tree_shaped_group_paths() {
        let mut rows = vec![
            sample_compare_row(
                Some("lib.Zed"),
                "Zed",
                "FUNCTION",
                "INTERNAL",
                ":lib",
                "test",
                ["TYPE_REF"],
            ),
            sample_compare_row(
                Some("app.Alpha"),
                "Alpha",
                "CLASS",
                "PUBLIC",
                ":app",
                "main",
                ["CALL"],
            ),
        ];

        sort_compare_rows(&mut rows, CompareSort::Module);

        assert_eq!(rows[0].fq_name.as_deref(), Some("app.Alpha"));
        assert_eq!(
            rows[0].group_path,
            vec![
                ":app".to_string(),
                "main".to_string(),
                "Alpha.kt".to_string()
            ]
        );
        assert_eq!(rows[1].depth, 3);
    }

    #[test]
    fn compare_view_mode_toggle_switches_between_full_and_difference() {
        assert_eq!(CompareViewMode::Full.toggle(), CompareViewMode::Difference);
        assert_eq!(CompareViewMode::Difference.toggle(), CompareViewMode::Full);
    }

    #[test]
    fn public_demo_enters_the_selected_story_on_enter() {
        let mut app = PublicDemoApp::new(sample_public_demo_snapshot());

        let outcome = app.on_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        assert_eq!(outcome, PublicDemoOutcome::Continue);
        assert_eq!(app.screen, PublicDemoScreen::Story);
        assert_eq!(
            app.selected_candidate().expect("selected candidate").fq_name,
            "lib.Foo"
        );
        assert_eq!(
            app.selected_chapter().expect("selected chapter").chapter,
            DemoChapter::SemanticDifference,
            "the story should open on the first available evidence chapter"
        );
    }

    #[test]
    fn public_demo_candidate_screen_renders_repo_specific_stories() {
        let app = PublicDemoApp::new(sample_public_demo_snapshot());
        let backend = ratatui::backend::TestBackend::new(100, 28);
        let mut terminal = Terminal::new(backend).expect("test terminal");

        terminal
            .draw(|frame| render_public_demo(frame, &app))
            .expect("render public demo");

        let rendered = terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>();
        assert!(
            rendered.contains("Choose a story from your codebase")
                && rendered.contains("Trace the impact of Foo")
                && rendered.contains("3 indexed evidence points")
                && rendered.contains("INDEX READY")
                && rendered.contains("READ ONLY"),
            "candidate screen should explain the real story choices: {rendered}"
        );
    }

    #[test]
    fn public_demo_semantic_signal_theme_assigns_color_by_meaning() {
        let theme = PublicDemoTheme::semantic_signal();

        assert_eq!(theme.compiler, Color::Cyan);
        assert_eq!(theme.index, Color::Magenta);
        assert_eq!(theme.success, Color::Green);
        assert_eq!(theme.plan, Color::Yellow);
    }

    #[test]
    fn public_demo_monochrome_theme_honors_no_color_surfaces() {
        let theme = PublicDemoTheme::monochrome();

        assert_eq!(theme.compiler, Color::Reset);
        assert_eq!(theme.index, Color::Reset);
        assert_eq!(theme.success, Color::Reset);
        assert_eq!(theme.plan, Color::Reset);
    }

    #[test]
    fn public_demo_remains_legible_at_standard_terminal_size() {
        let app = PublicDemoApp::new(sample_public_demo_snapshot());
        let backend = ratatui::backend::TestBackend::new(80, 24);
        let mut terminal = Terminal::new(backend).expect("test terminal");

        terminal
            .draw(|frame| render_public_demo_with_theme(frame, &app, PublicDemoTheme::monochrome()))
            .expect("render compact public demo");

        let rendered = terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>();
        assert!(rendered.contains("Kast Semantic Story") && rendered.contains("INDEX READY"));
    }
