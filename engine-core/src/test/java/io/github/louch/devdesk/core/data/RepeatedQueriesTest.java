package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepeatedQueriesTest {

    private static QueryLog.Query q(String sql, String params) {
        return new QueryLog.Query(Instant.EPOCH, 0.5, sql, params);
    }

    @Test
    void oneQueryPerItemIsFoundWithTheQueryThatLoadedTheList() {
        List<QueryLog.Query> request = new ArrayList<>();
        request.add(q("BEGIN", ""));
        request.add(q("select id, user_id from orders where status = 'open'", ""));
        for (int i = 1; i <= 50; i++) {
            request.add(q("select * from users where id = $1", "$1 = '" + i + "'"));
        }
        request.add(q("COMMIT", ""));
        var found = RepeatedQueries.find(request);
        assertThat(found).hasSize(1);
        var r = found.getFirst();
        assertThat(r.shape()).isEqualTo("select * from users where id = ?");
        assertThat(r.times()).isEqualTo(50);
        assertThat(r.distinctValues()).isEqualTo(50);
        assertThat(r.totalMs()).isEqualTo(25.0);
        assertThat(r.before()).contains("select id, user_id from orders where status = 'open'");
        assertThat(r.identical()).isFalse();
        assertThat(r.advice()).contains("where id in", "eager loading");
    }

    @Test
    void theSameQueryAgainAndAgainGetsOtherAdvice() {
        List<QueryLog.Query> request = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            request.add(q("select value from settings where key = 'currency'", ""));
        }
        var r = RepeatedQueries.find(request).getFirst();
        assertThat(r.identical()).isTrue();
        assertThat(r.advice()).contains("run it once");
    }

    @Test
    void aFewRepeatsAndTransactionStepsAreNormal() {
        List<QueryLog.Query> request = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            request.add(q("BEGIN", ""));
            request.add(q("insert into log values (" + i + ")", ""));
            request.add(q("COMMIT", ""));
        }
        assertThat(RepeatedQueries.find(request)).isEmpty();
    }
}
