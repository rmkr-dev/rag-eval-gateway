-- Sampled calls for offline evaluation, written only when gateway.eval is on and the database
-- sink is used. Prompts are stored as sent to the provider, after PII redaction; the original
-- values are never stored. Rows older than gateway.eval.retention are deleted.
CREATE TABLE eval_sample (
    id             VARCHAR(64)    NOT NULL PRIMARY KEY,
    started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    client         VARCHAR(64)    NOT NULL,
    model_alias    VARCHAR(128)   NOT NULL,
    provider       VARCHAR(64),
    provider_model VARCHAR(256),
    streamed       BOOLEAN        NOT NULL,
    question       TEXT           NOT NULL,
    context        TEXT           NOT NULL,  -- JSON array of passages
    answer         TEXT           NOT NULL,
    messages       TEXT           NOT NULL,  -- JSON array of {role, content}
    tags           TEXT           NOT NULL,  -- JSON array of strings
    pii_redactions INTEGER        NOT NULL DEFAULT 0,
    faithfulness   DOUBLE PRECISION,
    relevance      DOUBLE PRECISION,
    scored_at      TIMESTAMP WITH TIME ZONE
);

CREATE INDEX ix_eval_sample_started_at ON eval_sample (started_at);
CREATE INDEX ix_eval_sample_model_started_at ON eval_sample (model_alias, started_at);
CREATE INDEX ix_eval_sample_client_started_at ON eval_sample (client, started_at);
