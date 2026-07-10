#[cfg(test)]
mod tests {
    use super::*;

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
                && rendered.contains("3 indexed evidence points"),
            "candidate screen should explain the real story choices: {rendered}"
        );
    }

    #[test]
    fn public_demo_uses_tui_only_for_interactive_human_output() {
        assert!(should_run_public_demo_tui(OutputFormat::Human, true, true));
        assert!(!should_run_public_demo_tui(
            OutputFormat::Human,
            false,
            true
        ));
        assert!(!should_run_public_demo_tui(
            OutputFormat::Toon,
            true,
            true
        ));
    }

    fn sample_compare_row<const N: usize>(
        fq_name: Option<&str>,
        label: &str,
        kind: &str,
        visibility: &str,
        module_path: &str,
        source_set: &str,
        relation_kinds: [&str; N],
    ) -> CompareRow {
        let path = format!(
            "/workspace/{}/{}.kt",
            module_path.trim_start_matches(':'),
            label
        );
        let mut row = CompareRow {
            id: fq_name
                .map(|name| format!("symbol:{name}"))
                .unwrap_or_else(|| format!("lexical:{label}")),
            label: label.to_string(),
            fq_name: fq_name.map(str::to_string),
            kind: Some(kind.to_string()),
            visibility: Some(visibility.to_string()),
            path: Some(path),
            module_path: Some(module_path.to_string()),
            source_set: Some(source_set.to_string()),
            relation_kinds: relation_kinds
                .iter()
                .map(|value| value.to_string())
                .collect(),
            incoming_references: 1,
            outgoing_references: 2,
            group_path: Vec::new(),
            depth: 0,
            badge: CompareBadge::Common,
        };
        assign_compare_module_path(&mut row);
        row
    }

    fn sample_lexical_only_row(label: &str) -> CompareRow {
        let mut row = CompareRow {
            id: format!("lexical:/workspace/lib/{label}.md:{label}"),
            label: label.to_string(),
            fq_name: None,
            kind: None,
            visibility: None,
            path: Some(format!("/workspace/lib/{label}.md")),
            module_path: Some(":lib".to_string()),
            source_set: Some("main".to_string()),
            relation_kinds: Vec::new(),
            incoming_references: 0,
            outgoing_references: 0,
            group_path: Vec::new(),
            depth: 0,
            badge: CompareBadge::LexicalOnly,
        };
        assign_compare_module_path(&mut row);
        row
    }

    fn sample_public_demo_snapshot() -> PublicDemoSnapshot {
        PublicDemoSnapshot {
            response_type: "KAST_DEMO",
            ok: true,
            availability: PublicDemoAvailability::IndexOnly,
            workspace_root: "/workspace".to_string(),
            mutates: false,
            candidates: vec![DemoCandidate {
                kind: DemoCandidateKind::ImpactHub,
                fq_name: "lib.Foo".to_string(),
                title: "Trace the impact of Foo".to_string(),
                evidence_count: 3,
                file: Some("/workspace/lib/Foo.kt".to_string()),
                module: Some(":lib".to_string()),
            }],
            chapters: index_only_chapters(),
            help: vec![
                "kast agent impact --symbol lib.Foo --workspace-root <repo>".to_string(),
            ],
            schema_version: SCHEMA_VERSION,
        }
    }
}
