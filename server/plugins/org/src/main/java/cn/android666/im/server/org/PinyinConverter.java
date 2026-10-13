package cn.android666.im.server.org;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.Locale;
import java.util.Map;

/**
 * 姓名转拼音（《三端协议规范.md》§4.1 的 pinyin、initials 字段），用于通讯录排序和搜索。
 *
 * - 汉字取 pinyin4j 给出的第一个读音（通常是最常用的读音）；
 * - 多音字作姓氏时读音特殊（如"曾"读 zeng、"单"读 shan），姓名第一个字按 {@link #SURNAMES} 修正；
 * - 英文字母和数字原样转小写，一个连续的字母数字串算一个"字"，首字母取其第一个字符；
 * - 其他字符（空格、标点、·）忽略。
 */
public final class PinyinConverter {

    /**
     * 姓名转换结果。
     *
     * @param pinyin   全拼，小写无空格，如 {@code zhangsan}
     * @param initials 首字母，如 {@code zs}
     */
    public record Result(String pinyin, String initials) {
    }

    /** 多音字作姓氏时的读音 */
    private static final Map<Character, String> SURNAMES = Map.ofEntries(
            Map.entry('曾', "zeng"), Map.entry('单', "shan"), Map.entry('解', "xie"), Map.entry('仇', "qiu"),
            Map.entry('区', "ou"), Map.entry('朴', "piao"), Map.entry('查', "zha"), Map.entry('翟', "zhai"),
            Map.entry('覃', "qin"), Map.entry('盖', "ge"), Map.entry('乐', "yue"), Map.entry('种', "chong"),
            Map.entry('秘', "bi"), Map.entry('缪', "miao"), Map.entry('尉', "yu"), Map.entry('沈', "shen"),
            Map.entry('重', "chong"), Map.entry('柏', "bai"), Map.entry('宓', "mi"), Map.entry('任', "ren"),
            Map.entry('纪', "ji"), Map.entry('葛', "ge"), Map.entry('牟', "mou"), Map.entry('褚', "chu"),
            Map.entry('华', "hua"), Map.entry('祭', "zhai"), Map.entry('句', "gou"), Map.entry('长', "chang"),
            Map.entry('万', "wan"), Map.entry('石', "shi"), Map.entry('薄', "bo"), Map.entry('贾', "jia"),
            Map.entry('卜', "bu"), Map.entry('洗', "xian"), Map.entry('冼', "xian"), Map.entry('黑', "hei"));

    /** pinyin4j 输出格式：小写、不带声调、ü 写作 v */
    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinConverter() {
    }

    /**
     * 把姓名转为全拼和首字母。
     *
     * @param name 姓名
     * @return 转换结果；姓名为空时两个字段都是空字符串
     */
    public static Result convert(String name) {
        StringBuilder pinyin = new StringBuilder();
        StringBuilder initials = new StringBuilder();
        // 是否处于一个连续的字母数字串中（只在串的第一个字符处记首字母）
        boolean inAsciiRun = false;
        boolean first = true;
        for (char c : name.trim().toCharArray()) {
            if (isAsciiLetterOrDigit(c)) {
                char lower = Character.toLowerCase(c);
                pinyin.append(lower);
                if (!inAsciiRun) {
                    initials.append(lower);
                }
                inAsciiRun = true;
                first = false;
                continue;
            }
            inAsciiRun = false;
            String syllable = first ? SURNAMES.get(c) : null;
            if (syllable == null) {
                syllable = pinyinOf(c);
            }
            if (syllable != null && !syllable.isEmpty()) {
                pinyin.append(syllable);
                initials.append(syllable.charAt(0));
                first = false;
            }
        }
        return new Result(pinyin.toString(), initials.toString());
    }

    /**
     * 单个字符的拼音。
     *
     * @param c 字符
     * @return 第一个读音；不是汉字时返回 null
     */
    private static String pinyinOf(char c) {
        try {
            String[] readings = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
            return readings == null || readings.length == 0 ? null : readings[0].toLowerCase(Locale.ROOT);
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            // 格式组合固定且合法，不会发生
            throw new IllegalStateException(e);
        }
    }

    /**
     * 是否为 ASCII 字母或数字。
     *
     * @param c 字符
     * @return 是则为 true
     */
    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
