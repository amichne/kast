FROM mirror.gcr.io/library/python:3.12-slim-bookworm@sha256:392307d22300de8b5986851a12d9176dfc0fc073e65bf6523ebd7dcbeb23564e

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash ca-certificates coreutils curl debianutils findutils git grep gzip libdigest-sha-perl mawk procps sed tar unzip zsh \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m pip install --no-cache-dir --disable-pip-version-check jsonschema==4.26.0

WORKDIR /src
ENV PYTHONDONTWRITEBYTECODE=1
CMD ["python3", "packaging/run-portable-tests.py"]
