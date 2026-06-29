#[derive(Tabled)]
struct PathConfigFileRow {
    #[tabled(rename = "Scope")]
    scope: String,
    #[tabled(rename = "State")]
    state: String,
    #[tabled(rename = "Path")]
    path: String,
}

#[derive(Tabled)]
struct PathEntryRow {
    #[tabled(rename = "Key")]
    key: String,
    #[tabled(rename = "Source")]
    source: String,
    #[tabled(rename = "Kind")]
    kind: String,
    #[tabled(rename = "From")]
    from: String,
    #[tabled(rename = "State")]
    state: String,
    #[tabled(rename = "Value")]
    value: String,
}

#[derive(Tabled)]
struct IdeaPluginInstallSummaryRow {
    #[tabled(rename = "Item")]
    item: String,
    #[tabled(rename = "Value")]
    value: String,
}

#[derive(Tabled)]
struct IdeaPluginDirectoryRow {
    #[tabled(rename = "JetBrains plugins directory")]
    path: String,
}

fn render_table_with_style<Row>(
    rows: impl IntoIterator<Item = Row>,
    style: TableRenderStyle,
) -> String
where
    Row: Tabled,
{
    let mut table = Table::new(rows);
    match style {
        TableRenderStyle::Ascii => table.with(TableStyle::ascii()),
        TableRenderStyle::Modern => table.with(TableStyle::modern()),
    };
    table.to_string()
}

fn exists_label(exists: bool) -> &'static str {
    if exists { "exists" } else { "missing" }
}

fn format_bytes_for_output(bytes: u64) -> String {
    const KIB: u64 = 1024;
    const MIB: u64 = KIB * 1024;
    if bytes >= MIB {
        format!("{:.1} MiB", bytes as f64 / MIB as f64)
    } else if bytes >= KIB {
        format!("{:.1} KiB", bytes as f64 / KIB as f64)
    } else {
        format!("{bytes} B")
    }
}
