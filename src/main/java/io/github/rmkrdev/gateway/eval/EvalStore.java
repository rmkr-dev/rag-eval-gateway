package io.github.rmkrdev.gateway.eval;

import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.report.ReportQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code database} sink: reads and writes {@code eval_sample} rows with plain SQL that runs
 * on Postgres and H2.
 */
public class EvalStore implements EvalSink {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<ChatMessage>> MESSAGES = new TypeReference<>() {
    };

    private static final String INSERT = """
            INSERT INTO eval_sample (id, started_at, completed_at, client, model_alias, provider, provider_model,
                streamed, question, context, answer, messages, tags, pii_redactions)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT = """
            SELECT id, started_at, completed_at, client, model_alias, provider, provider_model, streamed, question,
                context, answer, messages, tags, pii_redactions, faithfulness, relevance, scored_at
            FROM eval_sample
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public EvalStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public String name() {
        return EvalProperties.DATABASE;
    }

    @Override
    public void write(List<EvalSample> samples) {
        jdbc.batchUpdate(INSERT, samples, samples.size(), (ps, s) -> {
            ps.setString(1, s.id());
            ps.setObject(2, utc(s.startedAt()));
            ps.setObject(3, utc(s.completedAt()));
            ps.setString(4, s.client());
            ps.setString(5, s.model());
            ps.setString(6, s.provider());
            ps.setString(7, s.providerModel());
            ps.setBoolean(8, s.streamed());
            ps.setString(9, s.question());
            ps.setString(10, json.writeValueAsString(s.context()));
            ps.setString(11, s.answer());
            ps.setString(12, json.writeValueAsString(s.messages()));
            ps.setString(13, json.writeValueAsString(s.tags()));
            ps.setInt(14, s.piiRedactions());
        });
    }

    public Optional<EvalSample> find(String id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::map, id).stream().findFirst();
    }

    /** Samples that started in the query's range, oldest first, at most {@code limit}. */
    public List<EvalSample> find(ReportQuery query, int limit) {
        List<Object> args = new ArrayList<>();
        String where = where(query, args);
        return jdbc.query(SELECT + where + " ORDER BY started_at, id FETCH FIRST " + Math.max(1, limit)
                + " ROWS ONLY", this::map, args.toArray());
    }

    /**
     * Sets the given scores on one sample; a {@code null} score leaves the stored one alone.
     *
     * @return whether the sample exists
     */
    public boolean score(String id, Double faithfulness, Double relevance, Instant at) {
        return jdbc.update("""
                UPDATE eval_sample SET faithfulness = COALESCE(?, faithfulness),
                    relevance = COALESCE(?, relevance), scored_at = ?
                WHERE id = ?
                """, ps -> {
            ps.setObject(1, faithfulness, Types.DOUBLE);
            ps.setObject(2, relevance, Types.DOUBLE);
            ps.setObject(3, utc(at));
            ps.setString(4, id);
        }) > 0;
    }

    /**
     * Sample counts and average scores per model alias or client, for samples that started in
     * the query's range.
     */
    public List<SummaryRow> summary(ReportQuery query, boolean byClient) {
        String key = byClient ? "client" : "model_alias";
        List<Object> args = new ArrayList<>();
        String where = where(query, args);
        return jdbc.query("SELECT " + key + " AS k, COUNT(*) AS samples, COUNT(faithfulness) AS faithfulness_n, "
                + "AVG(faithfulness) AS faithfulness, COUNT(relevance) AS relevance_n, AVG(relevance) AS relevance "
                + "FROM eval_sample" + where + " GROUP BY " + key + " ORDER BY " + key,
                (rs, row) -> new SummaryRow(rs.getString("k"), rs.getLong("samples"),
                        rs.getLong("faithfulness_n"), round(rs.getObject("faithfulness", Double.class)),
                        rs.getLong("relevance_n"), round(rs.getObject("relevance", Double.class))),
                args.toArray());
    }

    /** Deletes samples that started before {@code cutoff} and returns how many went. */
    public int deleteStartedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM eval_sample WHERE started_at < ?", utc(cutoff));
    }

    /**
     * One group of samples.
     *
     * @param key model alias or client
     * @param samples samples in the group
     * @param faithfulnessScored samples with a faithfulness score
     * @param faithfulness average faithfulness, or {@code null} when none is scored
     * @param relevanceScored samples with a relevance score
     * @param relevance average relevance, or {@code null} when none is scored
     */
    public record SummaryRow(String key, long samples, long faithfulnessScored, Double faithfulness,
            long relevanceScored, Double relevance) {
    }

    private static String where(ReportQuery query, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE started_at >= ? AND started_at < ?");
        args.add(utc(query.from()));
        args.add(utc(query.to()));
        if (query.client() != null) {
            where.append(" AND client = ?");
            args.add(query.client());
        }
        if (query.model() != null) {
            where.append(" AND model_alias = ?");
            args.add(query.model());
        }
        if (query.provider() != null) {
            where.append(" AND provider = ?");
            args.add(query.provider());
        }
        return where.toString();
    }

    private EvalSample map(ResultSet rs, int row) throws SQLException {
        Double faithfulness = rs.getObject("faithfulness", Double.class);
        Double relevance = rs.getObject("relevance", Double.class);
        OffsetDateTime scoredAt = rs.getObject("scored_at", OffsetDateTime.class);
        EvalSample.Scores scores = faithfulness == null && relevance == null && scoredAt == null ? null
                : new EvalSample.Scores(faithfulness, relevance, scoredAt == null ? null : scoredAt.toInstant());
        return new EvalSample(
                rs.getString("id"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                rs.getString("client"),
                rs.getString("model_alias"),
                rs.getString("provider"),
                rs.getString("provider_model"),
                rs.getBoolean("streamed"),
                rs.getString("question"),
                json.readValue(rs.getString("context"), STRINGS),
                rs.getString("answer"),
                json.readValue(rs.getString("messages"), MESSAGES),
                json.readValue(rs.getString("tags"), STRINGS),
                rs.getInt("pii_redactions"),
                scores);
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 10_000) / 10_000.0;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
