package cn.android666.im.server.org;

import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;

/** {@link PinyinConverter} 测试 */
public class PinyinConverterTest {

    @Test
    public void chineseName() {
        PinyinConverter.Result r = PinyinConverter.convert("张三");
        assertThat(r.pinyin()).isEqualTo("zhangsan");
        assertThat(r.initials()).isEqualTo("zs");
    }

    /** 多音字作姓氏时按姓氏读音 */
    @Test
    public void polyphonicSurname() {
        assertThat(PinyinConverter.convert("曾小明").pinyin()).isEqualTo("zengxiaoming");
        assertThat(PinyinConverter.convert("单田芳").pinyin()).isEqualTo("shantianfang");
        assertThat(PinyinConverter.convert("解晓东").initials()).isEqualTo("xxd");
    }

    /** 姓氏表只作用于第一个字 */
    @Test
    public void surnameOverrideOnlyFirstChar() {
        assertThat(PinyinConverter.convert("王曾").pinyin()).isEqualTo("wangceng");
    }

    /** ü 写作 v */
    @Test
    public void umlautAsV() {
        assertThat(PinyinConverter.convert("吕布").pinyin()).isEqualTo("lvbu");
    }

    /** 英文姓名：每个单词一个首字母，空格忽略 */
    @Test
    public void englishName() {
        PinyinConverter.Result r = PinyinConverter.convert("John Smith");
        assertThat(r.pinyin()).isEqualTo("johnsmith");
        assertThat(r.initials()).isEqualTo("js");
    }

    /** 中英混合、少数民族姓名中的间隔号 */
    @Test
    public void mixedAndSeparator() {
        assertThat(PinyinConverter.convert("李A2").pinyin()).isEqualTo("lia2");
        assertThat(PinyinConverter.convert("李A2").initials()).isEqualTo("la");
        PinyinConverter.Result r = PinyinConverter.convert("买买提·艾力");
        assertThat(r.pinyin()).isEqualTo("maimaitiaili");
        assertThat(r.initials()).isEqualTo("mmtal");
    }

    @Test
    public void empty() {
        PinyinConverter.Result r = PinyinConverter.convert("  ");
        assertThat(r.pinyin()).isEmpty();
        assertThat(r.initials()).isEmpty();
    }
}
