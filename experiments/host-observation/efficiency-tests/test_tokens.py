from agent_efficiency_tokens import Row, ratio, summary


def test_failed_trials_consume_tokens():
    rows = (
        Row(
            task="a",
            repeat=0,
            arm="control",
            correct=True,
            known_tokens=100,
            total_tokens=100,
            requests=1,
            tools=1,
        ),
        Row(
            task="b",
            repeat=0,
            arm="control",
            correct=False,
            known_tokens=300,
            total_tokens=300,
            requests=2,
            tools=1,
        ),
    )
    measured = summary(rows)
    assert measured.total_tokens == 400
    assert measured.tokens_per_correct_completion == 400
    assert measured.median_tokens == 200
    assert measured.failures == 1


def test_incomplete_usage_is_unknown_instead_of_free():
    rows = (
        Row(
            task="a",
            repeat=0,
            arm="treatment",
            correct=True,
            known_tokens=100,
            total_tokens=100,
            requests=1,
            tools=1,
        ),
        Row(
            task="b",
            repeat=0,
            arm="treatment",
            correct=False,
            known_tokens=25,
            total_tokens=None,
            requests=2,
            tools=1,
        ),
    )
    measured = summary(rows)
    assert measured.known_tokens == 125
    assert measured.total_tokens is None
    assert measured.tokens_per_correct_completion is None
    assert measured.median_tokens is None
    assert measured.incomplete_usage_trials == 1


def test_no_correct_completion_has_no_finite_efficiency():
    row = Row(
        task="a",
        repeat=0,
        arm="control",
        correct=False,
        known_tokens=100,
        total_tokens=100,
        requests=1,
        tools=1,
    )
    assert summary((row,)).tokens_per_correct_completion is None
    assert ratio(None, 100) is None
    assert ratio(100, 0) is None
    assert ratio(600, 100) == 6
