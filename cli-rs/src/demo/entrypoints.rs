pub fn run(args: DemoArgs) -> Result<i32> {
    match args.view {
        DemoView::Compare => run_compare_demo(args),
        DemoView::Symbol => run_symbol_demo(args),
    }
}

fn run_compare_demo(args: DemoArgs) -> Result<i32> {
    let request = DemoRequest::from_args(args)?;
    let mut db = DemoDatabase::open(request.clone())?;
    let initial_query = request
        .query
        .clone()
        .or_else(|| {
            request
                .symbol
                .as_deref()
                .map(simple_symbol_name)
                .map(str::to_string)
        })
        .unwrap_or_default();
    let snapshot = db.compare_snapshot(CompareSnapshotRequest {
        query: &initial_query,
        filters: &CompareFilters::default(),
        sort: CompareSort::Module,
        view_mode: CompareViewMode::Full,
        requested_symbol: request.symbol.as_deref(),
        selected_lexical: 0,
        selected_semantic: 0,
        active_pane: ComparePane::Semantic,
    })?;

    if request.json || !io::stdout().is_terminal() {
        return print_compare_json_snapshot(snapshot);
    }

    run_compare_tui(CompareApp::from_snapshot(db, request, snapshot))
}

fn run_symbol_demo(args: DemoArgs) -> Result<i32> {
    let request = DemoRequest::from_args(args)?;
    let mut db = DemoDatabase::open(request.clone())?;
    let snapshot = db.snapshot(
        request.symbol.as_deref(),
        request.query.as_deref().unwrap_or_default(),
        Vec::new(),
    )?;

    if request.json || !io::stdout().is_terminal() {
        return print_json_snapshot(snapshot);
    }

    run_demo_tui(DemoApp::from_snapshot(db, request, snapshot))
}

impl DemoRequest {
    fn from_args(args: DemoArgs) -> Result<Self> {
        let workspace_root = config::resolve_workspace_root(args.workspace_root)?;
        let database = args
            .database
            .map(config::normalize)
            .unwrap_or(config::workspace_database_path(&workspace_root)?);
        Ok(Self {
            workspace_root,
            database,
            symbol: args.symbol,
            query: args.query,
            limit: args.limit,
            json: args.json,
            backend_name: None,
        })
    }
}
