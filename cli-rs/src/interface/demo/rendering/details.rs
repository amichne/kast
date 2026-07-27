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
