package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SlowQueriesTest {

    @Test
    void aQueryAndItsShapeInTheListMatch() {
        String fromList = "SELECT o.id, o.total FROM orders o WHERE o.user_id = $1 AND status IN ($2, $3, $4)";
        String fromLog = "select o.id, o.total\n  from orders o -- the cart page\n where o.user_id = 42"
                + " and status in ('new', 'paid', 'it''s sent');";
        assertThat(SlowQueries.shape(fromLog)).isEqualTo(SlowQueries.shape(fromList));
        assertThat(SlowQueries.shape(fromList))
                .isEqualTo("select o.id, o.total from orders o where o.user_id = ? and status in (?)");
    }

    @Test
    void namesWithDigitsStayNames() {
        assertThat(SlowQueries.shape("select col1, t2.x from t2 limit 10"))
                .isEqualTo("select col1, t2.x from t2 limit ?");
    }

    @Test
    void rankIsThePlaceInTheList() {
        var list = new SlowQueries(
                SlowQueries.Status.READY,
                List.of(
                        new SlowQueries.Statement(
                                "select * from big", 3, 900, 300, 500, 30, SlowQueries.shape("select * from big")),
                        new SlowQueries.Statement(
                                "select * from users where id = $1",
                                1000,
                                50,
                                0.05,
                                1,
                                1000,
                                SlowQueries.shape("select * from users where id = $1"))),
                Optional.empty());
        assertThat(list.rankOf("SELECT * FROM users WHERE id = 7")).contains(1);
        assertThat(list.rankOf("select * from other")).isEmpty();
    }
}
