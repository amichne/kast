impl DemoApp {
    fn from_snapshot(db: DemoDatabase, request: DemoRequest, snapshot: DemoSnapshot) -> Self {
        Self {
            db,
            request,
            search_query: snapshot.query,
            search_results: snapshot.search_results,
            current: snapshot.current,
            incoming: snapshot.incoming,
            outgoing: snapshot.outgoing,
            preview: snapshot.preview,
            index: snapshot.index,
            trail: snapshot.trail,
            focus: DemoPane::Incoming,
            input_mode: InputMode::Navigate,
            selected_search: 0,
            selected_incoming: 0,
            selected_outgoing: 0,
            message: "Enter walks into a symbol. / searches. Tab changes pane. b goes back."
                .to_string(),
            should_quit: false,
        }
    }

    fn on_key(&mut self, key: KeyEvent) -> Result<()> {
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
            self.should_quit = true;
            return Ok(());
        }
        match self.input_mode {
            InputMode::Search => self.on_search_key(key),
            InputMode::Navigate => self.on_navigation_key(key),
        }
    }

    fn on_search_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Esc => {
                self.input_mode = InputMode::Navigate;
                self.message = "Search cancelled".to_string();
            }
            KeyCode::Enter => {
                self.input_mode = InputMode::Navigate;
                self.focus = DemoPane::Search;
                self.activate_selection()?;
            }
            KeyCode::Backspace => {
                self.search_query.pop();
                self.refresh_search()?;
            }
            KeyCode::Char(value) if !key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.search_query.push(value);
                self.refresh_search()?;
            }
            KeyCode::Up => {
                self.selected_search =
                    move_index(self.selected_search, self.search_results.len(), -1);
                self.refresh_preview();
            }
            KeyCode::Down => {
                self.selected_search =
                    move_index(self.selected_search, self.search_results.len(), 1);
                self.refresh_preview();
            }
            _ => {}
        }
        Ok(())
    }

    fn on_navigation_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Char('q') | KeyCode::Esc => self.should_quit = true,
            KeyCode::Char('/') => {
                self.search_query.clear();
                self.selected_search = 0;
                self.refresh_search()?;
                self.input_mode = InputMode::Search;
                self.focus = DemoPane::Search;
                self.message = "Type to search; Enter opens the selected symbol".to_string();
            }
            KeyCode::Tab => {
                self.focus = self.focus.next();
                self.refresh_preview();
            }
            KeyCode::BackTab => {
                self.focus = self.focus.previous();
                self.refresh_preview();
            }
            KeyCode::Left | KeyCode::Char('h') => {
                self.focus = self.focus.previous();
                self.refresh_preview();
            }
            KeyCode::Right | KeyCode::Char('l') => {
                self.focus = self.focus.next();
                self.refresh_preview();
            }
            KeyCode::Up | KeyCode::Char('k') => self.move_selection(-1),
            KeyCode::Down | KeyCode::Char('j') => self.move_selection(1),
            KeyCode::Enter => self.activate_selection()?,
            KeyCode::Char('b') | KeyCode::Backspace => self.back()?,
            KeyCode::Char('r') => self.reload_current()?,
            _ => {}
        }
        Ok(())
    }

    fn refresh_search(&mut self) -> Result<()> {
        self.search_results = self.db.search(&self.search_query, self.request.limit)?;
        self.selected_search = self
            .selected_search
            .min(self.search_results.len().saturating_sub(1));
        self.message = format!("{} symbol matches", self.search_results.len());
        self.refresh_preview();
        Ok(())
    }

    fn move_selection(&mut self, delta: isize) {
        match self.focus {
            DemoPane::Search => {
                self.selected_search =
                    move_index(self.selected_search, self.search_results.len(), delta);
            }
            DemoPane::Incoming => {
                self.selected_incoming =
                    move_index(self.selected_incoming, self.incoming.len(), delta);
            }
            DemoPane::Outgoing => {
                self.selected_outgoing =
                    move_index(self.selected_outgoing, self.outgoing.len(), delta);
            }
        }
        self.refresh_preview();
    }

    fn activate_selection(&mut self) -> Result<()> {
        match self.focus {
            DemoPane::Search => {
                if let Some(hit) = self.search_results.get(self.selected_search) {
                    self.open_symbol(&hit.fq_name.clone(), true)?;
                }
            }
            DemoPane::Incoming => {
                let relation = self.incoming.get(self.selected_incoming).cloned();
                self.open_relation(relation)?;
            }
            DemoPane::Outgoing => {
                let relation = self.outgoing.get(self.selected_outgoing).cloned();
                self.open_relation(relation)?;
            }
        }
        Ok(())
    }

    fn open_relation(&mut self, relation: Option<SymbolRelation>) -> Result<()> {
        let Some(relation) = relation else {
            self.message = "No relation selected".to_string();
            return Ok(());
        };
        if let Some(symbol) = relation.fq_name {
            self.open_symbol(&symbol, true)
        } else {
            self.preview = SourcePreview::from_location(
                relation.path.as_deref(),
                relation.offset,
                format!("{} reference", relation.edge_kind),
            );
            self.message = "This row is file-level only; no source symbol was indexed".to_string();
            Ok(())
        }
    }

    fn open_symbol(&mut self, fq_name: &str, push_current: bool) -> Result<()> {
        if push_current
            && let Some(current) = &self.current
            && current.fq_name != fq_name
        {
            self.trail.push(current.fq_name.clone());
            if self.trail.len() > 10 {
                self.trail.remove(0);
            }
        }
        self.load_symbol(fq_name)?;
        self.focus = DemoPane::Incoming;
        Ok(())
    }

    fn load_symbol(&mut self, fq_name: &str) -> Result<()> {
        let Some(detail) = self.db.symbol_detail(fq_name)? else {
            self.message = format!("Symbol not found in source-index.db: {fq_name}");
            return Ok(());
        };
        self.incoming = self
            .db
            .incoming_relations(&detail.fq_name, self.request.limit)?;
        self.outgoing = self
            .db
            .outgoing_relations(&detail.fq_name, self.request.limit)?;
        self.current = Some(detail);
        self.selected_incoming = 0;
        self.selected_outgoing = 0;
        self.index = self.db.index()?;
        self.refresh_preview();
        if let Some(current) = &self.current {
            self.message = format!(
                "{}: {} incoming, {} outgoing",
                current.simple_name, current.incoming_references, current.outgoing_references
            );
        }
        Ok(())
    }

    fn back(&mut self) -> Result<()> {
        if let Some(symbol) = self.trail.pop() {
            self.load_symbol(&symbol)?;
            self.message = format!("Back to {symbol}");
        } else {
            self.message = "No previous symbol in this walk".to_string();
        }
        Ok(())
    }

    fn reload_current(&mut self) -> Result<()> {
        if let Some(symbol) = self.current.as_ref().map(|symbol| symbol.fq_name.clone()) {
            self.load_symbol(&symbol)?;
            self.message = "Reloaded source-index.db view".to_string();
        }
        Ok(())
    }

    fn refresh_preview(&mut self) {
        self.preview = match self.focus {
            DemoPane::Search => self
                .search_results
                .get(self.selected_search)
                .map(|hit| {
                    SourcePreview::from_location(
                        hit.path.as_deref(),
                        hit.declaration_offset,
                        format!("Search hit: {}", hit.simple_name),
                    )
                })
                .unwrap_or_else(|| SourcePreview::message("No search hit selected")),
            DemoPane::Incoming => self
                .incoming
                .get(self.selected_incoming)
                .map(|relation| {
                    SourcePreview::from_location(
                        relation.path.as_deref(),
                        relation.offset,
                        format!("Incoming: {}", relation.simple_name),
                    )
                })
                .or_else(|| self.current_preview())
                .unwrap_or_else(|| SourcePreview::message("No incoming reference selected")),
            DemoPane::Outgoing => self
                .outgoing
                .get(self.selected_outgoing)
                .map(|relation| {
                    SourcePreview::from_location(
                        relation.path.as_deref(),
                        relation.offset,
                        format!("Outgoing: {}", relation.simple_name),
                    )
                })
                .or_else(|| self.current_preview())
                .unwrap_or_else(|| SourcePreview::message("No outgoing reference selected")),
        };
    }

    fn current_preview(&self) -> Option<SourcePreview> {
        self.current.as_ref().map(|symbol| {
            SourcePreview::from_location(
                symbol.path.as_deref(),
                symbol.declaration_offset,
                format!("Declaration: {}", symbol.simple_name),
            )
        })
    }
}
