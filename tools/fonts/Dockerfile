# Builds Apple Color Emoji (CBDT/CBLC) from the macOS sbix font.
# Network is needed only for `docker build`; `docker run` uses --network none.
# Python version follows the converter's .python-version.
FROM python:3.12.5-slim@sha256:c24c34b502635f1f7c4e99dc09a2cbd85d480b7dcfd077198c6b5af138906390

# Converter: third-party code, pinned to a reviewed commit (never a branch).
ARG CONVERTER_REPO=https://github.com/samuelngs/apple-emoji-ttf.git
ARG CONVERTER_SHA=484daf4e13942437e083d881cae39fbc92d837e1

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && git clone "$CONVERTER_REPO" /opt/apple-emoji-ttf \
    && git -C /opt/apple-emoji-ttf checkout --detach "$CONVERTER_SHA" \
    && rm -rf /opt/apple-emoji-ttf/.git \
    && apt-get purge -y git \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir -r /opt/apple-emoji-ttf/requirements.txt

COPY emoji.yaml verify.py derive_strike.py android_fixups.py entrypoint.sh /opt/reach/
RUN chmod +x /opt/reach/entrypoint.sh \
    && useradd --create-home builder
USER builder

ENTRYPOINT ["/opt/reach/entrypoint.sh"]
