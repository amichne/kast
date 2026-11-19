"""One Promptfoo participant in a fresh pytest process."""

import json
import os
import subprocess
from pathlib import Path

from promptfoo.prepare_corpus import examine


def test_delegate() -> None:
    directory = Path(os.environ["KAST_CORPUS_TRIAL"])
    request = json.loads((directory / "request.json").read_text())
    workspace = Path(request["workspace"])
    revision = request["revision"]
    repository = request["repository"]
    examine(workspace, revision, repository)
    worker = Path(__file__).with_name("corpus-participant.mjs")
    participant = subprocess.run(
        ["node", str(worker)],
        input=json.dumps(request),
        text=True,
        stdout=subprocess.PIPE,
        check=True,
    )
    response = json.loads(participant.stdout)
    (directory / "response.json").write_text(json.dumps(response))
    examine(workspace, revision, repository)
