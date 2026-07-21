#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn structured_output_renderer_emits_pretty_json() {
        let value = json!({
            "ok": true,
            "method": "agent/tools",
            "result": {
                "type": "KAST_AGENT_TOOLS",
                "tools": [
                    {"name": "kast_resolve", "method": "symbol/resolve"},
                    {"name": "kast_references", "method": "symbol/references"}
                ]
            },
            "schemaVersion": 3
        });

        let rendered =
            render_structured_output(&value, OutputFormat::Json).expect("render json output");

        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&rendered).expect("rendered json"),
            value
        );
        assert!(
            rendered.ends_with('\n'),
            "agent output should keep the existing trailing newline contract"
        );
    }

    #[test]
    fn readable_and_json_renderers_preserve_the_same_compact_agent_contract() {
        let value = json!({
            "type": "KAST_AGENT_DIAGNOSTICS_RESULT",
            "severityCounts": {"error": 1, "warning": 499, "info": 0, "total": 500},
            "cardinality": {
                "type": "EXACT",
                "totalCount": 500,
                "returnedCount": 8,
                "truncated": true,
                "nextPageToken": "00000000-0000-4000-8000-000000000337"
            }
        });

        let readable =
            render_structured_output(&value, OutputFormat::Human).expect("readable output");
        let json = render_structured_output(&value, OutputFormat::Json).expect("json output");

        assert_eq!(readable, json);
    }

    #[test]
    fn structured_output_renderer_can_emit_round_trippable_toon() {
        let value = json!({
            "ok": true,
            "method": "agent/tools",
            "result": {
                "type": "KAST_AGENT_TOOLS",
                "tools": [
                    {"name": "kast_resolve", "method": "symbol/resolve", "mutates": false},
                    {"name": "kast_references", "method": "symbol/references", "mutates": false},
                    {"name": "kast_rename", "method": "symbol/rename", "mutates": true}
                ]
            },
            "schemaVersion": 3
        });

        let rendered =
            render_structured_output(&value, OutputFormat::Toon).expect("render toon output");
        let decoded: serde_json::Value =
            toon_format::decode_default(rendered.trim()).expect("decode toon output");

        assert_eq!(decoded, value);
    }

    #[test]
    fn structured_output_renderer_toon_is_smaller_for_uniform_rows() {
        let value = json!({
            "tools": [
                {"name": "kast_resolve", "method": "symbol/resolve", "mutates": false},
                {"name": "kast_references", "method": "symbol/references", "mutates": false},
                {"name": "kast_rename", "method": "symbol/rename", "mutates": true},
                {"name": "kast_workspace_search", "method": "raw/workspace-search", "mutates": false}
            ]
        });

        let json_output =
            render_structured_output(&value, OutputFormat::Json).expect("render json output");
        let toon_output =
            render_structured_output(&value, OutputFormat::Toon).expect("render toon output");

        assert!(
            toon_output.len() < json_output.len(),
            "TOON should reduce repeated field names for uniform rows: json={}, toon={}",
            json_output.len(),
            toon_output.len()
        );
    }

    #[test]
    fn rendered_human_output_plain_text_does_not_dump_raw_markdown_tokens() {
        let rendered = render_markdown_for_test(
            "# Kast status\n\n- Workspace: `/tmp/kast`\n\n## Next steps\n- Open the IDE\n",
            RenderStyle::Plain,
        );

        assert!(
            rendered.starts_with("Kast status\n==========="),
            "primary heading should be rendered as text with an underline: {rendered}"
        );
        assert!(
            rendered.contains("Workspace: /tmp/kast"),
            "inline code markers should be rendered away: {rendered}"
        );
        assert!(
            rendered.contains("Next steps\n----------"),
            "secondary headings should be rendered as sections: {rendered}"
        );
        assert!(
            !rendered.contains("# Kast status") && !rendered.contains("`/tmp/kast`"),
            "raw Markdown control tokens should not leak into rendered output: {rendered}"
        );
    }

    #[test]
    fn rendered_human_output_ansi_styles_headings_and_inline_code() {
        let rendered = render_markdown_for_test(
            "# Kast status\n- Workspace: `/tmp/kast`\n",
            RenderStyle::Ansi,
        );

        assert!(
            rendered.contains("\x1b["),
            "ANSI rendering should style headings or inline code: {rendered:?}"
        );
        assert!(
            !rendered.contains("# Kast status") && !rendered.contains("`/tmp/kast`"),
            "ANSI rendering should still remove raw Markdown control tokens: {rendered:?}"
        );
    }

    #[test]
    fn path_resolution_human_output_uses_compact_lists_for_plain_capture() {
        let report = path_resolution_report_for_test();
        let mut document = MarkdownDocument::default();
        print_path_resolution(&mut document, &report);
        let rendered = render_markdown_for_test(&document.into_string(), RenderStyle::Plain);

        assert!(rendered.contains("Config files:"), "{rendered}");
        assert!(rendered.contains("Path entries:"), "{rendered}");
        assert!(rendered.contains("paths.installRoot"), "{rendered}");
        assert!(
            rendered.contains("- global: exists /tmp/config.toml"),
            "{rendered}"
        );
        assert!(
            rendered.contains("- paths.installRoot: exists directory via manifest -> /tmp/kast"),
            "{rendered}"
        );
        assert!(
            !rendered.lines().any(|line| line.starts_with('+')
                || line.starts_with('┌')
                || line.starts_with('└')),
            "path output should avoid table borders entirely: {rendered}"
        );
    }

    #[test]
    fn path_resolution_human_output_shortens_home_paths() {
        assert_eq!(compact_path_with_home("/tmp", Some("/tmp")), "~");
        assert_eq!(
            compact_path_with_home("/tmp/config.toml", Some("/tmp")),
            "~/config.toml"
        );
        assert_eq!(
            compact_path_with_home("/var/tmp/config.toml", Some("/tmp")),
            "/var/tmp/config.toml"
        );
    }

    fn path_resolution_report_for_test() -> crate::config::PathResolutionReport {
        crate::config::PathResolutionReport {
            root: "/tmp/kast".to_string(),
            config_files: vec![crate::config::PathResolutionConfigFile {
                scope: "global".to_string(),
                path: "/tmp/config.toml".to_string(),
                exists: true,
            }],
            entries: vec![crate::config::PathResolutionEntry {
                key: "paths.installRoot".to_string(),
                value: "/tmp/kast".to_string(),
                source: crate::config::PathResolutionSource::Manifest,
                owner: "install".to_string(),
                derived_from: None,
                exists: true,
                expected_kind: "directory".to_string(),
                used_by_idea: true,
            }],
            warnings: vec![],
            schema_version: 3,
        }
    }

    #[test]
    fn source_modules_render_as_plain_text_tree() {
        let rendered = render_source_modules_for_test(&[
            ":backend:idea",
            ":analysis-api",
            ":backend:headless",
            "secondary",
        ]);

        assert!(
            rendered.contains(
                "Source modules\n--------------\n- analysis-api\n- backend\n  - headless\n  - idea\n- secondary\n"
            ),
            "source modules should render as a sorted tree: {rendered}"
        );
        assert!(
            !rendered.contains("Source modules:"),
            "source modules should not render as a comma-separated list: {rendered}"
        );
    }

    #[test]
    fn source_modules_truncate_after_display_limit() {
        let modules = (0..32)
            .map(|index| format!(":module-{index:02}"))
            .collect::<Vec<_>>();
        let rendered = render_source_modules_for_owned_test(&modules);

        assert!(
            rendered.contains("- module-29"),
            "the thirtieth module should still render: {rendered}"
        );
        assert!(
            !rendered.contains("- module-30"),
            "modules after the display limit should be omitted: {rendered}"
        );
        assert!(
            rendered.contains("- ... 2 more modules"),
            "truncation summary should report hidden modules: {rendered}"
        );
    }

    fn render_source_modules_for_test(module_names: &[&str]) -> String {
        let module_names = module_names
            .iter()
            .map(|module_name| module_name.to_string())
            .collect::<Vec<_>>();
        render_source_modules_for_owned_test(&module_names)
    }

    fn render_source_modules_for_owned_test(module_names: &[String]) -> String {
        let mut document = MarkdownDocument::default();
        print_source_modules(&mut document, module_names);
        render_markdown_for_test(&document.into_string(), RenderStyle::Plain)
    }
}
