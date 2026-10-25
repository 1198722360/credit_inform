FROM python:3.12-alpine

WORKDIR /app
COPY requirements.txt /app/
RUN pip install --no-cache-dir -r requirements.txt
COPY relay_server.py gateway_client.py admin_web.html /app/

RUN addgroup -S -g 10001 relay && adduser -S -D -H -u 10001 -G relay relay \
    && mkdir -p /app/data /app/config \
    && chown -R relay:relay /app

USER relay
ENV HOST=0.0.0.0 \
    PORT=8047 \
    RELAY_CONFIG_FILE=/app/config/config.json \
    RELAY_DATA_FILE=/app/data/relay-state.json \
    MONITOR_STORE_FILE=/app/data/monitor-configs.json \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

EXPOSE 8047
VOLUME ["/app/config", "/app/data"]
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8047/health || exit 1

CMD ["python", "/app/relay_server.py"]
