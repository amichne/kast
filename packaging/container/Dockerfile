# syntax=docker/dockerfile:1
FROM debian:13.6-slim@sha256:d7e12182ce18b85b93007c1dedf31f2d29e01ccf3182cc4017c709b6259bc132 AS development
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash ca-certificates curl git python3 python3-venv python3-pip python3-defusedxml \
    build-essential zlib1g-dev unzip zip jq ripgrep less procps nodejs npm \
    fontconfig libxi6 libxrender1 libxtst6 libgtk-3-0 libnss3 libasound2t64 libgbm1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home --shell /bin/bash developer \
    && mkdir /work && chown developer:developer /work

FROM development AS assembly
COPY assets /opt/kast-build/assets
COPY bootstrap.zip install.sh /opt/kast-build/
COPY packaging /opt/kast-build/packaging
RUN python3 /opt/kast-build/packaging/container_install.py

FROM development
COPY --from=assembly --chown=developer:developer /opt/kast /opt/kast
COPY packaging/composed_bundle.py packaging/composition_platform.py packaging/managed_headless.py /opt/kast/packaging/
ENV PATH="/opt/kast/bin:/opt/kast/runtime/kast-analysis-host/jbr/bin:/home/developer/.local/bin:${PATH}" \
    JAVA_HOME="/opt/kast/runtime/kast-analysis-host/jbr" \
    HOME="/home/developer" \
    KAST_INSTALL_ROOT="/opt/kast/state/home/.local/share/kast"
LABEL io.kast.dev-container="1"
USER developer
WORKDIR /work
CMD ["bash"]
