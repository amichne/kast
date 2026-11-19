"""The participant receipt survives a failed post-run workspace check."""

import json
from types import SimpleNamespace

import pytest

from promptfoo import corpus_trial


def test_receipt_survives_post_run_workspace_rejection(tmp_path, monkeypatch):
    request = {
        "workspace": str(tmp_path / "workspace"),
        "revision": "a" * 40,
        "repository": "fixture",
    }
    (tmp_path / "request.json").write_text(json.dumps(request))
    monkeypatch.setenv("KAST_CORPUS_TRIAL", str(tmp_path))
    calls = []

    def examine(*args):
        calls.append(args)
        if len(calls) == 2:
            raise ValueError("workspace changed")

    response = {
        "output": "delivered answer",
        "metadata": {"accounting": {"fixture": "known receipt"}},
    }
    monkeypatch.setattr(corpus_trial, "examine", examine)

    def run_participant(*args, **kwargs):
        assert json.loads(kwargs["input"])["repository"] == request["repository"]
        return SimpleNamespace(stdout=json.dumps(response))

    monkeypatch.setattr(corpus_trial.subprocess, "run", run_participant)
    with pytest.raises(ValueError, match="workspace changed"):
        corpus_trial.test_delegate()
    assert len(calls) == 2
    assert json.loads((tmp_path / "response.json").read_text()) == response
