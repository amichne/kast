fn exists_label(exists: bool) -> &'static str {
    if exists { "exists" } else { "missing" }
}

fn compact_path_for_output(path: &str) -> String {
    let home = std::env::var("HOME").ok();
    compact_path_with_home(path, home.as_deref())
}

fn compact_path_with_home(path: &str, home: Option<&str>) -> String {
    let Some(home) = home else {
        return path.to_string();
    };
    if home.is_empty() {
        return path.to_string();
    }
    if path == home {
        return "~".to_string();
    }
    let home_prefix = format!("{home}/");
    path.strip_prefix(&home_prefix)
        .map(|suffix| format!("~/{suffix}"))
        .unwrap_or_else(|| path.to_string())
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
