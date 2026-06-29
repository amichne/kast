fn print_json_snapshot(snapshot: DemoSnapshot) -> Result<i32> {
    let response = DemoResponse {
        ok: true,
        snapshot,
        schema_version: SCHEMA_VERSION,
    };
    serde_json::to_writer_pretty(io::stdout(), &serde_json::to_value(response)?)?;
    println!();
    Ok(0)
}

fn print_compare_json_snapshot(snapshot: CompareSnapshot) -> Result<i32> {
    let response = CompareDemoResponse {
        ok: true,
        snapshot,
        schema_version: SCHEMA_VERSION,
    };
    serde_json::to_writer_pretty(io::stdout(), &serde_json::to_value(response)?)?;
    println!();
    Ok(0)
}

fn compare_row_from_detail(detail: SymbolDetail, badge: CompareBadge) -> CompareRow {
    let relation_kinds = detail.by_edge_kind.keys().cloned().collect();
    let path = detail.path.clone();
    let label = detail.simple_name.clone();
    let mut row = CompareRow {
        id: format!("symbol:{}", detail.fq_name),
        label,
        fq_name: Some(detail.fq_name),
        kind: detail.kind,
        visibility: detail.visibility,
        path,
        module_path: detail.module_path,
        source_set: detail.source_set,
        relation_kinds,
        incoming_references: detail.incoming_references,
        outgoing_references: detail.outgoing_references,
        group_path: Vec::new(),
        depth: 0,
        badge,
    };
    assign_compare_module_path(&mut row);
    row
}

fn apply_compare_filters(rows: &[CompareRow], filters: &CompareFilters) -> Vec<CompareRow> {
    rows.iter()
        .filter(|row| {
            filter_matches(&row.kind, &filters.kind)
                && filter_matches(&row.visibility, &filters.visibility)
                && filter_matches(&row.source_set, &filters.source_set)
                && filter_matches(&row.module_path, &filters.module)
                && filters
                    .relation
                    .as_ref()
                    .is_none_or(|relation| row.relation_kinds.iter().any(|kind| kind == relation))
        })
        .cloned()
        .collect()
}

fn filter_matches(value: &Option<String>, selected: &Option<String>) -> bool {
    selected
        .as_ref()
        .is_none_or(|selected| value.as_ref() == Some(selected))
}

fn build_compare_diff_buckets(
    lexical_rows: &[CompareRow],
    semantic_rows: &[CompareRow],
    semantic_filtered: &[CompareRow],
) -> CompareDiffBuckets {
    let lexical_keys: BTreeSet<_> = lexical_rows.iter().map(compare_row_key).collect();
    let semantic_keys: BTreeSet<_> = semantic_rows.iter().map(compare_row_key).collect();
    let filtered_keys: BTreeSet<_> = semantic_filtered.iter().map(compare_row_key).collect();

    let mut lexical_only: Vec<_> = lexical_rows
        .iter()
        .filter(|row| !semantic_keys.contains(&compare_row_key(row)))
        .cloned()
        .map(|mut row| {
            row.badge = CompareBadge::LexicalOnly;
            row
        })
        .collect();
    let mut semantic_only: Vec<_> = semantic_rows
        .iter()
        .filter(|row| !lexical_keys.contains(&compare_row_key(row)))
        .cloned()
        .map(|mut row| {
            row.badge = CompareBadge::SemanticOnly;
            row
        })
        .collect();
    let mut filtered_out: Vec<_> = semantic_rows
        .iter()
        .filter(|row| {
            let key = compare_row_key(row);
            lexical_keys.contains(&key) && !filtered_keys.contains(&key)
        })
        .cloned()
        .map(|mut row| {
            row.badge = CompareBadge::FilteredOut;
            row
        })
        .collect();
    sort_compare_rows(&mut lexical_only, CompareSort::Module);
    sort_compare_rows(&mut semantic_only, CompareSort::Module);
    sort_compare_rows(&mut filtered_out, CompareSort::Module);

    CompareDiffBuckets {
        lexical_only,
        semantic_only,
        filtered_out,
        common_count: lexical_keys.intersection(&semantic_keys).count(),
    }
}

fn selected_compare_row<'a>(
    requested_symbol: Option<&str>,
    left_rows: &'a [CompareRow],
    right_rows: &'a [CompareRow],
    selected_lexical: usize,
    selected_semantic: usize,
    active_pane: ComparePane,
) -> Option<(ComparePane, usize, &'a CompareRow)> {
    let requested = requested_symbol.and_then(|symbol| {
        right_rows
            .iter()
            .enumerate()
            .find(|(_, row)| row.fq_name.as_deref() == Some(symbol))
            .map(|(index, row)| (ComparePane::Semantic, index, row))
    });
    let lexical = left_rows
        .get(selected_lexical)
        .map(|row| (ComparePane::Lexical, selected_lexical, row));
    let semantic = right_rows
        .get(selected_semantic)
        .map(|row| (ComparePane::Semantic, selected_semantic, row));

    requested.or(match active_pane {
        ComparePane::Lexical => lexical.or(semantic),
        ComparePane::Semantic => semantic.or(lexical),
    })
}

fn apply_compare_badges(rows: &mut [CompareRow], other_rows: &[CompareRow], left_side: bool) {
    let other_keys: BTreeSet<_> = other_rows.iter().map(compare_row_key).collect();
    for row in rows {
        row.badge = if other_keys.contains(&compare_row_key(row)) {
            CompareBadge::Common
        } else if left_side {
            CompareBadge::LexicalOnly
        } else {
            CompareBadge::SemanticOnly
        };
    }
}

fn sort_compare_rows(rows: &mut [CompareRow], sort: CompareSort) {
    for row in rows.iter_mut() {
        assign_compare_module_path(row);
    }
    rows.sort_by(|left, right| match sort {
        CompareSort::Module => compare_module_tuple(left).cmp(&compare_module_tuple(right)),
        CompareSort::Visibility => compare_optional(&left.visibility, &right.visibility)
            .then_with(|| compare_module_tuple(left).cmp(&compare_module_tuple(right))),
        CompareSort::Kind => compare_optional(&left.kind, &right.kind)
            .then_with(|| compare_module_tuple(left).cmp(&compare_module_tuple(right))),
        CompareSort::Alphabetical => left
            .label
            .cmp(&right.label)
            .then_with(|| compare_row_key(left).cmp(&compare_row_key(right))),
    });
}

fn compare_optional(left: &Option<String>, right: &Option<String>) -> std::cmp::Ordering {
    left.as_deref()
        .unwrap_or("")
        .cmp(right.as_deref().unwrap_or(""))
}

fn compare_module_tuple(row: &CompareRow) -> (String, String, String, String) {
    (
        row.module_path.clone().unwrap_or_default(),
        row.source_set.clone().unwrap_or_default(),
        row.path
            .as_deref()
            .map(simple_file_name)
            .unwrap_or("")
            .to_string(),
        row.label.clone(),
    )
}

fn assign_compare_module_path(row: &mut CompareRow) {
    row.group_path = vec![
        row.module_path
            .clone()
            .unwrap_or_else(|| "workspace".to_string()),
        row.source_set.clone().unwrap_or_else(|| "main".to_string()),
        row.path
            .as_deref()
            .map(simple_file_name)
            .unwrap_or(&row.label)
            .to_string(),
    ];
    row.depth = row.group_path.len();
}

fn compare_row_key(row: &CompareRow) -> String {
    row.fq_name
        .clone()
        .unwrap_or_else(|| format!("lexical:{}", row.id))
}

fn compare_filter_snapshot(filters: &CompareFilters, rows: &[CompareRow]) -> CompareFilterSnapshot {
    CompareFilterSnapshot {
        chips: vec![
            compare_filter_chip(
                "kind",
                "Kind",
                &filters.kind,
                rows.iter().filter_map(|row| row.kind.clone()),
                "magenta",
            ),
            compare_filter_chip(
                "visibility",
                "Visibility",
                &filters.visibility,
                rows.iter().filter_map(|row| row.visibility.clone()),
                "yellow",
            ),
            compare_filter_chip(
                "sourceSet",
                "Source set",
                &filters.source_set,
                rows.iter().filter_map(|row| row.source_set.clone()),
                "cyan",
            ),
            compare_filter_chip(
                "module",
                "Module",
                &filters.module,
                rows.iter().filter_map(|row| row.module_path.clone()),
                "green",
            ),
            compare_filter_chip(
                "relation",
                "Relation",
                &filters.relation,
                rows.iter().flat_map(|row| row.relation_kinds.clone()),
                "blue",
            ),
        ],
    }
}

fn compare_filter_chip<I>(
    key: &'static str,
    label: &'static str,
    selected: &Option<String>,
    values: I,
    color: &'static str,
) -> CompareFilterChip
where
    I: Iterator<Item = String>,
{
    let mut unique = BTreeSet::new();
    unique.extend(values);
    let mut options = vec!["any".to_string()];
    options.extend(unique);
    CompareFilterChip {
        key,
        label,
        selected: selected.clone().unwrap_or_else(|| "any".to_string()),
        options,
        color,
    }
}

fn collect_relations<I>(rows: rusqlite::Result<I>) -> Result<Vec<SymbolRelation>>
where
    I: Iterator<Item = rusqlite::Result<SymbolRelation>>,
{
    let mut values = Vec::new();
    for row in rows.map_err(sql_error)? {
        values.push(row.map_err(sql_error)?);
    }
    Ok(values)
}

fn string_column<I>(rows: rusqlite::Result<I>) -> Result<Vec<String>>
where
    I: Iterator<Item = rusqlite::Result<String>>,
{
    let mut values = Vec::new();
    for row in rows.map_err(sql_error)? {
        values.push(row.map_err(sql_error)?);
    }
    Ok(values)
}

fn move_index(current: usize, len: usize, delta: isize) -> usize {
    if len == 0 {
        return 0;
    }
    if delta < 0 {
        current.saturating_sub(delta.unsigned_abs()).min(len - 1)
    } else {
        current.saturating_add(delta as usize).min(len - 1)
    }
}

fn line_number_for_offset(content: &str, offset: usize) -> usize {
    let capped = offset.min(content.len());
    content.as_bytes()[..capped]
        .iter()
        .filter(|byte| **byte == b'\n')
        .count()
        + 1
}

fn edge_summary(edges: &BTreeMap<String, i64>) -> String {
    if edges.is_empty() {
        return "-".to_string();
    }
    edges
        .iter()
        .map(|(kind, count)| format!("{}={count}", compact_kind(kind)))
        .collect::<Vec<_>>()
        .join(" ")
}

fn compact_kind(kind: &str) -> String {
    match kind {
        "TYPE_REF" => "TYPE".to_string(),
        "FUNCTION" => "FUNC".to_string(),
        "PROPERTY" => "PROP".to_string(),
        "INTERFACE" => "IFACE".to_string(),
        "ENUM_CLASS" => "ENUM".to_string(),
        other => truncate_chars(other, 8),
    }
}

fn compare_chip_color(color: &str) -> Color {
    match color {
        "magenta" => Color::Magenta,
        "yellow" => Color::Yellow,
        "cyan" => Color::Cyan,
        "green" => Color::Green,
        "blue" => Color::Blue,
        _ => Color::White,
    }
}

fn compare_badge_label(badge: &CompareBadge) -> &'static str {
    match badge {
        CompareBadge::Common => "=",
        CompareBadge::LexicalOnly => "lexical",
        CompareBadge::SemanticOnly => "semantic",
        CompareBadge::FilteredOut => "filtered",
    }
}

fn compare_badge_style(badge: &CompareBadge) -> Style {
    match badge {
        CompareBadge::Common => Style::default().fg(Color::DarkGray),
        CompareBadge::LexicalOnly => Style::default().fg(Color::Magenta),
        CompareBadge::SemanticOnly => Style::default().fg(Color::Green),
        CompareBadge::FilteredOut => Style::default().fg(Color::Yellow),
    }
}

fn simple_symbol_name(fq_name: &str) -> &str {
    fq_name.rsplit('.').next().unwrap_or(fq_name)
}

fn compact_namespace(fq_name: &str) -> String {
    fq_name
        .rsplit_once('.')
        .map(|(namespace, _)| truncate_chars(namespace, 42))
        .unwrap_or_default()
}

fn simple_file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

fn compact_path(path: &str) -> String {
    let parts: Vec<_> = path.split('/').filter(|part| !part.is_empty()).collect();
    if parts.len() <= 4 {
        return path.to_string();
    }
    format!(
        ".../{}/{}/{}/{}",
        parts[parts.len() - 4],
        parts[parts.len() - 3],
        parts[parts.len() - 2],
        parts[parts.len() - 1]
    )
}

fn truncate_chars(value: &str, max: usize) -> String {
    if value.chars().count() <= max {
        return value.to_string();
    }
    let mut result: String = value.chars().take(max.saturating_sub(3)).collect();
    result.push_str("...");
    result
}

fn sql_error(error: rusqlite::Error) -> CliError {
    CliError::new("SQLITE_ERROR", error.to_string())
}
