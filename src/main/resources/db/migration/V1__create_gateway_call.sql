-- One row per gateway call. No prompt or answer text is ever stored.
CREATE TABLE gateway_call (
    id                 VARCHAR(64)    NOT NULL PRIMARY KEY,
    started_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    client             VARCHAR(64)    NOT NULL,
    model_alias        VARCHAR(128)   NOT NULL,
    provider           VARCHAR(64),
    provider_model     VARCHAR(256),
    streamed           BOOLEAN        NOT NULL,
    status             VARCHAR(16)    NOT NULL,
    error              VARCHAR(64),
    finish_reason      VARCHAR(32),
    prompt_tokens      INTEGER        NOT NULL,
    completion_tokens  INTEGER        NOT NULL,
    cache_read_tokens  INTEGER,
    cache_write_tokens INTEGER,
    cost               NUMERIC(18, 8),
    currency           CHAR(3),
    price_version      VARCHAR(32),
    ttft_ms            BIGINT,
    total_ms           BIGINT         NOT NULL,
    failed_attempts    INTEGER        NOT NULL
);

-- Reports filter on a time range, usually grouped by client, alias or provider.
CREATE INDEX ix_gateway_call_started_at ON gateway_call (started_at);
CREATE INDEX ix_gateway_call_client_started_at ON gateway_call (client, started_at);
CREATE INDEX ix_gateway_call_model_started_at ON gateway_call (model_alias, started_at);
CREATE INDEX ix_gateway_call_provider_started_at ON gateway_call (provider, started_at);
