package io.github.rmkrdev.gateway.tracking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads and writes {@code gateway_call} rows with plain SQL that runs on Postgres and H2. */
public class CallStore {

    private static final String INSERT = """
            INSERT INTO gateway_call (id, started_at, client, model_alias, provider, provider_model, streamed,
                status, error, finish_reason, prompt_tokens, completion_tokens, cache_read_tokens,
                cache_write_tokens, cost, currency, price_version, ttft_ms, total_ms, failed_attempts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT = """
            SELECT id, started_at, client, model_alias, provider, provider_model, streamed, status, error,
                finish_reason, prompt_tokens, completion_tokens, cache_read_tokens, cache_write_tokens, cost,
                currency, price_version, ttft_ms, total_ms, failed_attempts
            FROM gateway_call
            """;

    private final JdbcTemplate jdbc;

    public CallStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(List<CallRecord> records) {
        jdbc.batchUpdate(INSERT, records, records.size(), (ps, r) -> {
            ps.setString(1, r.id());
            ps.setObject(2, utc(r.startedAt()));
            ps.setString(3, r.client());
            ps.setString(4, r.model());
            ps.setString(5, r.provider());
            ps.setString(6, r.providerModel());
            ps.setBoolean(7, r.streamed());
            ps.setString(8, r.status());
            ps.setString(9, r.error());
            ps.setString(10, r.finishReason());
            ps.setInt(11, r.promptTokens());
            ps.setInt(12, r.completionTokens());
            ps.setObject(13, r.cacheReadTokens(), java.sql.Types.INTEGER);
            ps.setObject(14, r.cacheWriteTokens(), java.sql.Types.INTEGER);
            ps.setBigDecimal(15, r.cost());
            ps.setString(16, r.currency());
            ps.setString(17, r.priceVersion());
            ps.setObject(18, r.timeToFirstTokenMs(), java.sql.Types.BIGINT);
            ps.setLong(19, r.totalMs());
            ps.setInt(20, r.failedAttempts());
        });
    }

    public Optional<CallRecord> find(String id) {
        return jdbc.query(SELECT + " WHERE id = ?", CallStore::map, id).stream().findFirst();
    }

    /** Most recent calls first. */
    public List<CallRecord> recent(int limit) {
        return jdbc.query(SELECT + " ORDER BY started_at DESC, id FETCH FIRST " + Math.max(1, limit) + " ROWS ONLY",
                CallStore::map);
    }

    /** Deletes calls that started before {@code cutoff} and returns how many went. */
    public int deleteStartedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM gateway_call WHERE started_at < ?", utc(cutoff));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static CallRecord map(ResultSet rs, int row) throws SQLException {
        return new CallRecord(
                rs.getString("id"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getString("client"),
                rs.getString("model_alias"),
                rs.getString("provider"),
                rs.getString("provider_model"),
                rs.getBoolean("streamed"),
                rs.getString("status"),
                rs.getString("error"),
                rs.getString("finish_reason"),
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getObject("cache_read_tokens", Integer.class),
                rs.getObject("cache_write_tokens", Integer.class),
                rs.getBigDecimal("cost"),
                rs.getString("currency"),
                rs.getString("price_version"),
                rs.getObject("ttft_ms", Long.class),
                rs.getLong("total_ms"),
                rs.getInt("failed_attempts"));
    }
}
