# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
FROM debian:13.6-slim@sha256:d7e12182ce18b85b93007c1dedf31f2d29e01ccf3182cc4017c709b6259bc132 AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl git perl python3 \
    libasound2t64 libfontconfig1 libgbm1 libgtk-3-0 libnss3 libxi6 libxrender1 libxtst6 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home --shell /bin/bash kast \
    && install -d -o kast -g kast /opt/kast /workspace
ENV HOME=/home/kast
USER kast
WORKDIR /workspace

FROM runtime AS installed
ARG KAST_VERSION
ARG BUNDLE_SHA256
RUN --network=none --mount=type=bind,from=bundle,target=/bundle \
    set -eu; \
    archive="/bundle/kast-composed-setup-v${KAST_VERSION}-linux-x86_64.tar.gz"; \
    printf '%s  %s\n' "$BUNDLE_SHA256" "$archive" | sha256sum --check --strict; \
    scratch="$(mktemp -d)"; \
    tar -xzf "$archive" -C "$scratch" --wildcards 'setup-kast/packaging/*' 'setup-kast/bin/setup-kast'; \
    "$scratch/setup-kast/bin/setup-kast" --archive "$archive" --archive-sha256 "$BUNDLE_SHA256" \
        --root /opt/kast/runtime --version "$KAST_VERSION"; \
    rm -rf "$scratch"

FROM runtime
COPY --from=installed --chown=kast:kast /opt/kast/runtime /opt/kast/runtime
COPY composed_bundle.py composition_platform.py managed_headless.py /opt/kast/packaging/
ENV PATH=/opt/kast/runtime/bin:$PATH
CMD ["bash"]
