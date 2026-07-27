#[test]
fn plain_package_segments_match_the_kotlin_l_and_nd_producer_boundary() {
    for accepted in [
        "named:ǅelta.ʰello",
        "named:例子.工具",
        "named:क.a١",
        "named:_private.a9",
    ] {
        assert_typed_boundary(&["--package", accepted]);
    }

    for rejected in [
        "named:ͅmark",
        "named:a.ͅmark",
        "named:Ⅻvalue",
        "named:a.Ⅻvalue",
        "named:²value",
        "named:a.²value",
    ] {
        assert_usage_error(&["--package", rejected]);
    }
}
