FROM mwader/static-ffmpeg:7.1 AS ffmpeg
FROM python:3.14-slim
COPY --from=ffmpeg /ffmpeg /ffprobe /usr/local/bin/
COPY ca.crt /ca.crt
ENV PIP_CERT=/ca.crt SSL_CERT_FILE=/ca.crt REQUESTS_CA_BUNDLE=/ca.crt PIP_DISABLE_PIP_VERSION_CHECK=1
# MA installa al volo le dipendenze dei servizi che si aggiungono (uv pip install): qui senza virtualenv.
ENV UV_SYSTEM_PYTHON=1
COPY requirements.txt /requirements.txt
RUN pip install --no-cache-dir -r /requirements.txt
COPY server /src
RUN pip install --no-cache-dir --no-deps /src
# Il servizio finto per provare da Xonos la configurazione dei servizi (modulo, accesso nel browser, opzioni).
COPY xonos_test /usr/local/lib/python3.14/site-packages/music_assistant/providers/xonos_test
CMD ["mass", "--data-dir", "/data", "--cache-dir", "/cache"]
