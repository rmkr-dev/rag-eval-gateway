package io.github.rmkrdev.gateway.report;

import io.github.rmkrdev.gateway.tracking.CallRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the calls a report covers with plain SQL that runs on Postgres and H2. Rows are streamed
 * with a fetch size inside a read-only transaction, so Postgres uses a cursor and never holds
 * the whole range in memory.
 */
class ReportStore {

    static final int FETCH_SIZE = 1000;

    private static final String COLUMNS = """
            started_at, client, model_alias, provider, status, prompt_tokens, completion_tokens,
            cache_read_tokens, cache_write_tokens, cost, currency, ttft_ms, total_ms
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;

    ReportStore(JdbcTemplate jdbc, TransactionTemplate readOnly) {
        this.jdbc = jdbc;
        this.readOnly = readOnly;
    }

    long count(ReportQuery query) {
        Where where = where(query);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM gateway_call" + where.sql, Long.class,
                where.args.toArray());
        return count == null ? 0 : count;
    }

    void forEach(ReportQuery query, Consumer<ReportRow> consumer) {
        Where where = where(query);
        readOnly.executeWithoutResult(status -> jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT " + COLUMNS + " FROM gateway_call" + where.sql);
            ps.setFetchSize(FETCH_SIZE);
            for (int i = 0; i < where.args.size(); i++) {
                ps.setObject(i + 1, where.args.get(i));
            }
            return ps;
        }, rs -> {
            Number ttft = (Number) rs.getObject("ttft_ms");
            Number cacheRead = (Number) rs.getObject("cache_read_tokens");
            Number cacheWrite = (Number) rs.getObject("cache_write_tokens");
            consumer.accept(new ReportRow(
                    rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                    rs.getString("client"),
                    rs.getString("model_alias"),
                    rs.getString("provider"),
                    CallRecord.OK.equals(rs.getString("status")),
                    rs.getInt("prompt_tokens"),
                    rs.getInt("completion_tokens"),
                    cacheRead == null ? null : cacheRead.intValue(),
                    cacheWrite == null ? null : cacheWrite.intValue(),
                    rs.getBigDecimal("cost"),
                    rs.getString("currency"),
                    ttft == null ? null : ttft.longValue(),
                    rs.getLong("total_ms")));
        }));
    }

    private static Where where(ReportQuery query) {
        StringBuilder sql = new StringBuilder(" WHERE started_at >= ? AND started_at < ?");
        List<Object> args = new ArrayList<>();
        args.add(query.from().atOffset(ZoneOffset.UTC));
        args.add(query.to().atOffset(ZoneOffset.UTC));
        if (query.client() != null) {
            sql.append(" AND client = ?");
            args.add(query.client());
        }
        if (query.model() != null) {
            sql.append(" AND model_alias = ?");
            args.add(query.model());
        }
        if (query.provider() != null) {
            sql.append(" AND provider = ?");
            args.add(query.provider());
        }
        return new Where(sql.toString(), args);
    }

    private record Where(String sql, List<Object> args) {
    }
}
