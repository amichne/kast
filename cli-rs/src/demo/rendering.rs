fn render_demo(frame: &mut Frame<'_>, app: &DemoApp) {
    let root = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(5),
            Constraint::Min(10),
            Constraint::Length(2),
        ])
        .split(frame.area());
    render_header(frame, root[0], app);
    render_body(frame, root[1], app);
    render_footer(frame, root[2], app);
}

fn render_compare_demo(frame: &mut Frame<'_>, app: &CompareApp) {
    let root = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(7),
            Constraint::Min(10),
            Constraint::Length(2),
        ])
        .split(frame.area());
    render_compare_header(frame, root[0], app);
    render_compare_body(frame, root[1], app);
    render_compare_footer(frame, root[2], app);
}

fn render_compare_header(frame: &mut Frame<'_>, area: Rect, app: &CompareApp) {
    let search_style = if app.focus == CompareFocus::Search {
        Style::default().fg(Color::Black).bg(Color::Cyan)
    } else {
        Style::default().fg(Color::Cyan)
    };
    let chips = app
        .snapshot
        .filters
        .chips
        .iter()
        .enumerate()
        .map(|(index, chip)| {
            let active = app.focus == CompareFocus::Filters && app.active_filter == index;
            Span::styled(
                format!(" {}:{} ", chip.label, chip.selected),
                Style::default()
                    .fg(compare_chip_color(chip.color))
                    .add_modifier(if active {
                        Modifier::REVERSED
                    } else {
                        Modifier::empty()
                    }),
            )
        })
        .collect::<Vec<_>>();
    let sort_style = if app.focus == CompareFocus::Sort {
        Style::default()
            .fg(Color::Black)
            .bg(Color::Yellow)
            .add_modifier(Modifier::BOLD)
    } else {
        Style::default().fg(Color::Yellow)
    };
    let lines = vec![
        Line::from(vec![
            Span::styled(
                "Kast Search Compare",
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw("  "),
            Span::styled(format!(" search: {} ", app.query), search_style),
        ]),
        Line::from(chips),
        Line::from(vec![
            Span::styled("sort ", Style::default().fg(Color::DarkGray)),
            Span::styled(format!("{:?}", app.sort).to_lowercase(), sort_style),
            Span::raw(format!(
                "  view {:?}  common {}  lexical-only {}  semantic-only {}  filtered {}",
                app.view_mode,
                app.snapshot.diff_buckets.common_count,
                app.snapshot.diff_buckets.lexical_only.len(),
                app.snapshot.diff_buckets.semantic_only.len(),
                app.snapshot.diff_buckets.filtered_out.len()
            )),
        ]),
        Line::from(vec![
            Span::styled("focus ", Style::default().fg(Color::DarkGray)),
            Span::styled(app.focus.title(), Style::default().fg(Color::Green)),
            Span::raw(format!("  {}", app.message)),
        ]),
    ];
    frame.render_widget(
        Paragraph::new(lines).block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(Style::default().fg(Color::DarkGray)),
        ),
        area,
    );
}

fn render_header(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let current = app
        .current
        .as_ref()
        .map(|symbol| symbol.fq_name.as_str())
        .unwrap_or("no symbol");
    let lines = vec![
        Line::from(vec![
            Span::styled(
                "Kast Symbol Walk",
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw("  "),
            Span::styled(
                current.to_string(),
                Style::default()
                    .fg(Color::Yellow)
                    .add_modifier(Modifier::BOLD),
            ),
        ]),
        Line::from(vec![
            Span::styled("focus ", Style::default().fg(Color::DarkGray)),
            Span::styled(app.focus.title(), Style::default().fg(Color::Green)),
            Span::raw(format!(
                "  symbols {}  files {}  refs {}  confidence {}",
                app.index.symbol_count,
                app.index.file_count,
                app.index.reference_count,
                app.index.confidence.level
            )),
        ]),
        Line::from(vec![Span::raw(&app.message)]),
    ];
    frame.render_widget(
        Paragraph::new(lines).block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(Style::default().fg(Color::DarkGray)),
        ),
        area,
    );
}

fn render_body(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    if area.width < 110 {
        let rows = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Percentage(28),
                Constraint::Percentage(36),
                Constraint::Percentage(36),
            ])
            .split(area);
        render_search(frame, rows[0], app);
        render_symbol_and_relations(frame, rows[1], app);
        render_preview(frame, rows[2], app);
        return;
    }

    let columns = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage(28),
            Constraint::Percentage(38),
            Constraint::Percentage(34),
        ])
        .split(area);
    let left = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Percentage(72), Constraint::Percentage(28)])
        .split(columns[0]);
    render_search(frame, left[0], app);
    render_trail(frame, left[1], app);
    render_symbol_and_relations(frame, columns[1], app);
    render_preview(frame, columns[2], app);
}

fn render_compare_body(frame: &mut Frame<'_>, area: Rect, app: &CompareApp) {
    if area.width < 110 {
        let rows = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Percentage(32),
                Constraint::Percentage(32),
                Constraint::Percentage(36),
            ])
            .split(area);
        render_compare_rows(
            frame,
            rows[0],
            &app.snapshot.left_pane,
            app.selected_lexical,
            app.focus == CompareFocus::Lexical,
            app.sort,
        );
        render_compare_rows(
            frame,
            rows[1],
            &app.snapshot.right_pane,
            app.selected_semantic,
            app.focus == CompareFocus::Semantic,
            app.sort,
        );
        render_source_preview(frame, rows[2], &app.snapshot.preview);
        return;
    }

    let columns = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage(32),
            Constraint::Percentage(34),
            Constraint::Percentage(34),
        ])
        .split(area);
    render_compare_rows(
        frame,
        columns[0],
        &app.snapshot.left_pane,
        app.selected_lexical,
        app.focus == CompareFocus::Lexical,
        app.sort,
    );
    render_compare_rows(
        frame,
        columns[1],
        &app.snapshot.right_pane,
        app.selected_semantic,
        app.focus == CompareFocus::Semantic,
        app.sort,
    );
    render_source_preview(frame, columns[2], &app.snapshot.preview);
}

fn render_compare_rows(
    frame: &mut Frame<'_>,
    area: Rect,
    pane: &ComparePaneSnapshot,
    selected: usize,
    focused: bool,
    sort: CompareSort,
) {
    let items: Vec<ListItem<'_>> = pane
        .rows
        .iter()
        .map(|row| {
            let indent = if sort == CompareSort::Module {
                "  ".repeat(row.depth.saturating_sub(1))
            } else {
                String::new()
            };
            ListItem::new(Line::from(vec![
                Span::styled(
                    format!("{}{: <12}", indent, compare_badge_label(&row.badge)),
                    compare_badge_style(&row.badge),
                ),
                Span::styled(
                    row.label.clone(),
                    Style::default()
                        .fg(Color::White)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::raw(format!(
                    "  {} {} {} in {} out {}",
                    row.kind.as_deref().unwrap_or("-"),
                    row.visibility.as_deref().unwrap_or("-"),
                    row.module_path.as_deref().unwrap_or("-"),
                    row.incoming_references,
                    row.outgoing_references
                )),
            ]))
        })
        .collect();
    render_list(
        frame,
        area,
        pane.title.to_string(),
        items,
        selected,
        focused,
    );
}

fn render_search(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let title = if app.input_mode == InputMode::Search {
        format!("/ {}", app.search_query)
    } else if app.search_query.is_empty() {
        "Symbols".to_string()
    } else {
        format!("Symbols matching {}", app.search_query)
    };
    let items: Vec<ListItem<'_>> = app
        .search_results
        .iter()
        .map(|hit| {
            let kind = hit.kind.as_deref().unwrap_or("SYMBOL");
            let module = hit.module_path.as_deref().unwrap_or("");
            ListItem::new(Line::from(vec![
                Span::styled(
                    format!("{:<10}", compact_kind(kind)),
                    Style::default().fg(Color::Magenta),
                ),
                Span::styled(
                    hit.simple_name.clone(),
                    Style::default()
                        .fg(Color::White)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::raw(format!(
                    "  in {} out {} {}",
                    hit.incoming_references, hit.outgoing_references, module
                )),
            ]))
        })
        .collect();
    render_list(
        frame,
        area,
        title,
        items,
        app.selected_search,
        app.focus == DemoPane::Search,
    );
}

fn render_compare_footer(frame: &mut Frame<'_>, area: Rect, app: &CompareApp) {
    let text = format!(
        "focus {} | type query | Enter search/apply | Tab focus | arrows select/cycle | v full/difference | q quit | db {}",
        app.focus.title(),
        compact_path(&app.request.database.display().to_string())
    );
    frame.render_widget(
        Paragraph::new(text)
            .block(Block::default().borders(Borders::TOP))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_trail(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let lines = if app.trail.is_empty() {
        vec![Line::from("No previous symbols yet")]
    } else {
        app.trail
            .iter()
            .rev()
            .map(|symbol| {
                Line::from(vec![
                    Span::styled(
                        simple_symbol_name(symbol).to_string(),
                        Style::default().fg(Color::Yellow),
                    ),
                    Span::raw(format!("  {}", compact_namespace(symbol))),
                ])
            })
            .collect()
    };
    frame.render_widget(
        Paragraph::new(lines)
            .block(Block::default().title("Walk Stack").borders(Borders::ALL))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_symbol_and_relations(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let rows = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(8),
            Constraint::Percentage(46),
            Constraint::Percentage(46),
        ])
        .split(area);
    render_current_symbol(frame, rows[0], app);
    render_relations(
        frame,
        rows[1],
        "Incoming: who breaks if this changes",
        &app.incoming,
        app.selected_incoming,
        app.focus == DemoPane::Incoming,
    );
    render_relations(
        frame,
        rows[2],
        "Outgoing: what this symbol touches",
        &app.outgoing,
        app.selected_outgoing,
        app.focus == DemoPane::Outgoing,
    );
}

fn render_current_symbol(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let lines = app
        .current
        .as_ref()
        .map(|symbol| {
            vec![
                Line::from(vec![
                    Span::styled(
                        symbol.simple_name.clone(),
                        Style::default()
                            .fg(Color::Yellow)
                            .add_modifier(Modifier::BOLD),
                    ),
                    Span::raw(format!("  {}", symbol.kind.as_deref().unwrap_or("SYMBOL"))),
                ]),
                Line::from(symbol.fq_name.clone()),
                Line::from(format!(
                    "refs: {} incoming / {} outgoing",
                    symbol.incoming_references, symbol.outgoing_references
                )),
                Line::from(format!(
                    "module: {}  visibility: {}",
                    symbol.module_path.as_deref().unwrap_or("-"),
                    symbol.visibility.as_deref().unwrap_or("-")
                )),
                Line::from(format!("edges: {}", edge_summary(&symbol.by_edge_kind))),
            ]
        })
        .unwrap_or_else(|| vec![Line::from("No symbol selected")]);
    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .title("Current Symbol")
                    .borders(Borders::ALL),
            )
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_relations(
    frame: &mut Frame<'_>,
    area: Rect,
    title: &str,
    relations: &[SymbolRelation],
    selected: usize,
    focused: bool,
) {
    let items: Vec<ListItem<'_>> = relations
        .iter()
        .map(|relation| {
            let walk_marker = if relation.walkable { ">" } else { "-" };
            ListItem::new(Line::from(vec![
                Span::styled(
                    format!("{walk_marker} {:<8}", compact_kind(&relation.edge_kind)),
                    Style::default().fg(Color::Green),
                ),
                Span::styled(
                    relation.simple_name.clone(),
                    Style::default()
                        .fg(Color::White)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::raw(format!(
                    "  {} refs  {}",
                    relation.references,
                    relation
                        .path
                        .as_deref()
                        .map(simple_file_name)
                        .unwrap_or("-")
                )),
            ]))
        })
        .collect();
    render_list(frame, area, title.to_string(), items, selected, focused);
}

fn render_preview(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    render_source_preview(frame, area, &app.preview);
}

fn render_source_preview(frame: &mut Frame<'_>, area: Rect, preview: &SourcePreview) {
    let mut lines = Vec::new();
    if let Some(path) = &preview.path {
        lines.push(Line::from(vec![
            Span::styled(compact_path(path), Style::default().fg(Color::Yellow)),
            Span::raw(
                preview
                    .focused_line
                    .map(|line| format!(":{line}"))
                    .unwrap_or_default(),
            ),
        ]));
        lines.push(Line::from(""));
    }
    if let Some(message) = &preview.message {
        lines.extend(message.lines().map(|line| Line::from(line.to_string())));
    } else {
        for line in &preview.lines {
            let number_style = if line.highlighted {
                Style::default()
                    .fg(Color::Yellow)
                    .add_modifier(Modifier::BOLD)
            } else {
                Style::default().fg(Color::DarkGray)
            };
            let text_style = if line.highlighted {
                Style::default().fg(Color::Black).bg(Color::Yellow)
            } else {
                Style::default()
            };
            lines.push(Line::from(vec![
                Span::styled(format!("{:>5} | ", line.number), number_style),
                Span::styled(line.text.clone(), text_style),
            ]));
        }
    }
    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .title(preview.title.clone())
                    .borders(Borders::ALL),
            )
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_footer(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let mode = match app.input_mode {
        InputMode::Navigate => "navigate",
        InputMode::Search => "search",
    };
    let text = format!(
        "mode {mode} | / search | Tab pane | Enter walk/open | b back | r reload | q quit | db {}",
        compact_path(&app.request.database.display().to_string())
    );
    frame.render_widget(
        Paragraph::new(text)
            .block(Block::default().borders(Borders::TOP))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_list(
    frame: &mut Frame<'_>,
    area: Rect,
    title: String,
    items: Vec<ListItem<'_>>,
    selected: usize,
    focused: bool,
) {
    let border_style = if focused {
        Style::default().fg(Color::Cyan)
    } else {
        Style::default().fg(Color::DarkGray)
    };
    let mut state = ListState::default();
    if !items.is_empty() {
        state.select(Some(selected.min(items.len().saturating_sub(1))));
    }
    let list = List::new(items)
        .block(
            Block::default()
                .title(title)
                .borders(Borders::ALL)
                .border_style(border_style),
        )
        .highlight_style(
            Style::default()
                .fg(Color::Black)
                .bg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )
        .highlight_symbol("> ");
    frame.render_stateful_widget(list, area, &mut state);
}
