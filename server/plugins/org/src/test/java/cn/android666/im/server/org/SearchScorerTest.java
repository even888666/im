package cn.android666.im.server.org;

import org.junit.Test;

import static cn.android666.im.server.org.Fixtures.employee;
import static com.google.common.truth.Truth.assertThat;

/** {@link SearchScorer} 测试 */
public class SearchScorerTest {

    @Test
    public void nameMatches() {
        assertThat(SearchScorer.score("张三", employee("1", "张三", "d"), "")).isEqualTo(100);
        assertThat(SearchScorer.score("张", employee("1", "张三", "d"), "")).isEqualTo(90);
        assertThat(SearchScorer.score("三", employee("1", "张三", "d"), "")).isEqualTo(70);
    }

    @Test
    public void pinyinAndInitials() {
        assertThat(SearchScorer.score("zs", employee("1", "张三", "d"), "")).isEqualTo(85);
        assertThat(SearchScorer.score("zhang", employee("1", "张三", "d"), "")).isEqualTo(75);
        assertThat(SearchScorer.score("z", employee("1", "张三", "d"), "")).isEqualTo(75);
    }

    @Test
    public void usernameMobileDept() {
        assertThat(SearchScorer.score("10086", employee("10086", "张三", "d"), "")).isEqualTo(95);
        assertThat(SearchScorer.score("100", employee("10086", "张三", "d"), "")).isEqualTo(80);
        assertThat(SearchScorer.score("8000", employee("1", "张三", "d", "13800001111"), "")).isEqualTo(60);
        // 手机号至少 3 位才匹配，避免输入一个数字匹配所有人
        assertThat(SearchScorer.score("38", employee("1", "张三", "d", "13800001111"), "")).isEqualTo(0);
        assertThat(SearchScorer.score("研发", employee("1", "张三", "d"), "总部/研发中心/Android组")).isEqualTo(50);
    }

    @Test
    public void noMatch() {
        assertThat(SearchScorer.score("李", employee("1", "张三", "d"), "研发中心")).isEqualTo(0);
    }
}
