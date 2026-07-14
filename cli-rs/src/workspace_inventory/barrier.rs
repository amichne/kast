pub(super) fn collect_with_single_retry<T>(mut collect: impl FnMut() -> (T, bool)) -> (T, bool) {
    let first = collect();
    if first.1 {
        return first;
    }
    collect()
}
