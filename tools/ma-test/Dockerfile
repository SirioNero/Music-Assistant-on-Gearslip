FROM mwader/static-ffmpeg:7.1 AS ffmpeg
FROM python:3.14-slim
COPY --from=ffmpeg /ffmpeg /ffprobe /usr/local/bin/
COPY ca.crt /ca.crt
ENV PIP_CERT=/ca.crt SSL_CERT_FILE=/ca.crt REQUESTS_CA_BUNDLE=/ca.crt PIP_DISABLE_PIP_VERSION_CHECK=1
COPY requirements.txt /requirements.txt
RUN pip install --no-cache-dir -r /requirements.txt
COPY server /src
RUN pip install --no-cache-dir --no-deps /src
CMD ["mass", "--data-dir", "/data", "--cache-dir", "/cache"]
