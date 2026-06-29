impl CompareApp {
    fn from_snapshot(db: DemoDatabase, request: DemoRequest, snapshot: CompareSnapshot) -> Self {
        Self {
            db,
            request,
            query: snapshot.query.clone(),
            filters: CompareFilters::default(),
            sort: snapshot.sort,
            view_mode: snapshot.view_mode,
            snapshot,
            focus: CompareFocus::Search,
            active_filter: 0,
            selected_lexical: 0,
            selected_semantic: 0,
            message: "Type a query, Enter searches, Tab reaches filters, v toggles differences."
                .to_string(),
            should_quit: false,
        }
    }

    fn on_key(&mut self, key: KeyEvent) -> Result<()> {
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
            self.should_quit = true;
            return Ok(());
        }
        match key.code {
            KeyCode::Char('q') | KeyCode::Esc if self.focus != CompareFocus::Search => {
                self.should_quit = true
            }
            KeyCode::Tab => self.focus = self.focus.next(),
            KeyCode::BackTab => self.focus = self.focus.previous(),
            KeyCode::Char('v') | KeyCode::Char('V') => {
                self.view_mode = self.view_mode.toggle();
                self.refresh_snapshot()?;
                self.message = format!("View mode: {:?}", self.view_mode);
            }
            _ => match self.focus {
                CompareFocus::Search => self.on_search_key(key)?,
                CompareFocus::Filters => self.on_filter_key(key)?,
                CompareFocus::Sort => self.on_sort_key(key)?,
                CompareFocus::Lexical => self.on_pane_key(key, true)?,
                CompareFocus::Semantic => self.on_pane_key(key, false)?,
            },
        }
        Ok(())
    }

    fn on_search_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Esc => self.should_quit = true,
            KeyCode::Enter => {
                self.selected_lexical = 0;
                self.selected_semantic = 0;
                self.refresh_snapshot()?;
                self.message = format!(
                    "{} lexical, {} semantic",
                    self.snapshot.left_pane.rows.len(),
                    self.snapshot.right_pane.rows.len()
                );
            }
            KeyCode::Backspace => {
                self.query.pop();
            }
            KeyCode::Char(value) if !key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.query.push(value);
            }
            KeyCode::Down => self.focus = CompareFocus::Semantic,
            _ => {}
        }
        Ok(())
    }

    fn on_filter_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Left | KeyCode::Char('h') => {
                self.active_filter = self.active_filter.saturating_sub(1);
            }
            KeyCode::Right | KeyCode::Char('l') => {
                self.active_filter = (self.active_filter + 1).min(4);
            }
            KeyCode::Enter | KeyCode::Char(' ') => {
                self.cycle_active_filter()?;
            }
            KeyCode::Down => self.focus = CompareFocus::Lexical,
            _ => {}
        }
        Ok(())
    }

    fn on_sort_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Left | KeyCode::Char('h') => self.sort = self.sort.previous(),
            KeyCode::Right | KeyCode::Char('l') | KeyCode::Enter | KeyCode::Char(' ') => {
                self.sort = self.sort.next()
            }
            KeyCode::Down => self.focus = CompareFocus::Lexical,
            _ => {}
        }
        self.refresh_snapshot()
    }

    fn on_pane_key(&mut self, key: KeyEvent, lexical: bool) -> Result<()> {
        match key.code {
            KeyCode::Up | KeyCode::Char('k') => {
                self.move_selection(lexical, -1);
                self.refresh_snapshot()?;
            }
            KeyCode::Down | KeyCode::Char('j') => {
                self.move_selection(lexical, 1);
                self.refresh_snapshot()?;
            }
            KeyCode::Left | KeyCode::Char('h') if !lexical => {
                self.focus = CompareFocus::Lexical;
                self.refresh_snapshot()?;
            }
            KeyCode::Right | KeyCode::Char('l') if lexical => {
                self.focus = CompareFocus::Semantic;
                self.refresh_snapshot()?;
            }
            KeyCode::Enter => self.refresh_snapshot()?,
            _ => {}
        }
        Ok(())
    }

    fn cycle_active_filter(&mut self) -> Result<()> {
        let chips = &self.snapshot.filters.chips;
        let Some(chip) = chips.get(self.active_filter) else {
            return Ok(());
        };
        let current = chip.selected.as_str();
        let index = chip
            .options
            .iter()
            .position(|option| option == current)
            .unwrap_or(0);
        let next = chip.options[(index + 1) % chip.options.len()].clone();
        let value = if next == "any" { None } else { Some(next) };
        match chip.key {
            "kind" => self.filters.kind = value,
            "visibility" => self.filters.visibility = value,
            "sourceSet" => self.filters.source_set = value,
            "module" => self.filters.module = value,
            "relation" => self.filters.relation = value,
            _ => {}
        }
        self.selected_semantic = 0;
        self.refresh_snapshot()
    }

    fn move_selection(&mut self, lexical: bool, delta: isize) {
        if lexical {
            self.selected_lexical = move_index(
                self.selected_lexical,
                self.snapshot.left_pane.rows.len(),
                delta,
            );
        } else {
            self.selected_semantic = move_index(
                self.selected_semantic,
                self.snapshot.right_pane.rows.len(),
                delta,
            );
        }
    }

    fn refresh_snapshot(&mut self) -> Result<()> {
        self.snapshot = self.db.compare_snapshot(CompareSnapshotRequest {
            query: &self.query,
            filters: &self.filters,
            sort: self.sort,
            view_mode: self.view_mode,
            requested_symbol: None,
            selected_lexical: self.selected_lexical,
            selected_semantic: self.selected_semantic,
            active_pane: self.focus.compare_pane(),
        })?;
        self.selected_lexical = self
            .selected_lexical
            .min(self.snapshot.left_pane.rows.len().saturating_sub(1));
        self.selected_semantic = self
            .selected_semantic
            .min(self.snapshot.right_pane.rows.len().saturating_sub(1));
        Ok(())
    }
}

impl CompareFocus {
    fn next(self) -> Self {
        match self {
            Self::Search => Self::Filters,
            Self::Filters => Self::Sort,
            Self::Sort => Self::Lexical,
            Self::Lexical => Self::Semantic,
            Self::Semantic => Self::Search,
        }
    }

    fn previous(self) -> Self {
        match self {
            Self::Search => Self::Semantic,
            Self::Filters => Self::Search,
            Self::Sort => Self::Filters,
            Self::Lexical => Self::Sort,
            Self::Semantic => Self::Lexical,
        }
    }

    fn title(self) -> &'static str {
        match self {
            Self::Search => "search",
            Self::Filters => "filters",
            Self::Sort => "sort",
            Self::Lexical => "lexical",
            Self::Semantic => "semantic",
        }
    }

    fn compare_pane(self) -> ComparePane {
        match self {
            Self::Lexical => ComparePane::Lexical,
            Self::Search | Self::Filters | Self::Sort | Self::Semantic => ComparePane::Semantic,
        }
    }
}

impl DemoPane {
    fn next(self) -> Self {
        match self {
            Self::Search => Self::Incoming,
            Self::Incoming => Self::Outgoing,
            Self::Outgoing => Self::Search,
        }
    }

    fn previous(self) -> Self {
        match self {
            Self::Search => Self::Outgoing,
            Self::Incoming => Self::Search,
            Self::Outgoing => Self::Incoming,
        }
    }

    fn title(self) -> &'static str {
        match self {
            Self::Search => "search",
            Self::Incoming => "incoming",
            Self::Outgoing => "outgoing",
        }
    }
}

impl SourcePreview {
    fn from_location(path: Option<&str>, offset: Option<i64>, title: String) -> Self {
        let Some(path) = path else {
            return Self::message(format!(
                "{title}\nNo file path was recorded for this symbol."
            ));
        };
        let content = match fs::read_to_string(path) {
            Ok(content) => content,
            Err(error) => {
                return Self {
                    title,
                    path: Some(path.to_string()),
                    focused_line: None,
                    lines: Vec::new(),
                    message: Some(format!("Cannot read {}: {error}", compact_path(path))),
                };
            }
        };
        let focus = offset
            .filter(|value| *value >= 0)
            .map(|value| line_number_for_offset(&content, value as usize))
            .unwrap_or(1);
        let source_lines: Vec<&str> = if content.is_empty() {
            vec![""]
        } else {
            content.lines().collect()
        };
        let total = source_lines.len().max(1);
        let focused_line = focus.clamp(1, total);
        let start = focused_line.saturating_sub(PREVIEW_RADIUS + 1);
        let end = (focused_line + PREVIEW_RADIUS).min(total);
        let lines = source_lines[start..end]
            .iter()
            .enumerate()
            .map(|(index, text)| {
                let number = start + index + 1;
                PreviewLine {
                    number,
                    text: truncate_chars(text, 180),
                    highlighted: number == focused_line,
                }
            })
            .collect();
        Self {
            title,
            path: Some(path.to_string()),
            focused_line: Some(focused_line),
            lines,
            message: None,
        }
    }

    fn message(message: impl Into<String>) -> Self {
        Self {
            title: "Source preview".to_string(),
            path: None,
            focused_line: None,
            lines: Vec::new(),
            message: Some(message.into()),
        }
    }
}
