package cn.android666.im.server.biz;

import org.junit.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** {@link SensitiveWordFilter} 测试 */
public class SensitiveWordFilterTest {

    private final SensitiveWordFilter filter = new SensitiveWordFilter(List.of("赌博", "Fa Piao", "代开发票", "  ", "赌博"));

    @Test
    public void size_ignoresBlankAndDuplicates() {
        assertThat(filter.size()).isEqualTo(3);
    }

    @Test
    public void find_basic() {
        assertThat(filter.find("周末一起去赌博吧")).isEqualTo("赌博");
        assertThat(filter.find("正常的工作消息")).isNull();
        assertThat(filter.find(null)).isNull();
        assertThat(filter.find("")).isNull();
    }

    /** 忽略大小写、空格、标点和符号（防止插字绕过） */
    @Test
    public void find_normalized() {
        assertThat(filter.find("需要 FAPIAO 吗")).isEqualTo("fapiao");
        assertThat(filter.find("赌 博")).isEqualTo("赌博");
        assertThat(filter.find("赌-博！")).isEqualTo("赌博");
        assertThat(filter.find("代·开·发·票")).isEqualTo("代开发票");
        assertThat(filter.find("赌😀博")).isEqualTo("赌博");
    }

    /** 前缀重叠时也能找到 */
    @Test
    public void find_overlapping() {
        SensitiveWordFilter f = new SensitiveWordFilter(List.of("abcd", "bc"));
        assertThat(f.find("xabce")).isEqualTo("bc");
        // 从最左边的位置开始匹配
        assertThat(f.find("abcd")).isEqualTo("abcd");
    }

    @Test
    public void empty() {
        assertThat(SensitiveWordFilter.EMPTY.find("任何内容")).isNull();
        assertThat(SensitiveWordFilter.EMPTY.size()).isEqualTo(0);
    }

    @Test
    public void normalize() {
        assertThat(SensitiveWordFilter.normalize(" A b\tC，。!?（）【】\n")).isEqualTo("abc");
        assertThat(SensitiveWordFilter.normalize("10086")).isEqualTo("10086");
    }
}
