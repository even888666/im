package cn.android666.im.server.biz;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 敏感词匹配（协议规范 §6.4）：字典树，对文本的每个位置向后查找，复杂度 O(文本长度 × 最长词长度)。
 *
 * 匹配前对文本和敏感词做相同的归一化：转小写、去掉空白和常见标点（防止在字之间插入空格、符号绕过，决策记录 A110）。
 * 不可变对象，修改词库时整体替换，读取不加锁。
 */
public final class SensitiveWordFilter {

    /** 空词库 */
    public static final SensitiveWordFilter EMPTY = new SensitiveWordFilter(List.of());

    /** 字典树节点 */
    private static final class Node {
        /** 子节点：码点 → 节点 */
        final Map<Integer, Node> children = new HashMap<>();

        /** 是否为某个词的结尾 */
        boolean end;
    }

    /** 根节点 */
    private final Node root = new Node();

    /** 词数 */
    private final int size;

    /**
     * 创建过滤器。
     *
     * @param words 敏感词（空白词忽略）
     */
    public SensitiveWordFilter(Collection<String> words) {
        int count = 0;
        for (String word : words) {
            String normalized = normalize(word);
            if (normalized.isEmpty()) {
                continue;
            }
            Node node = root;
            for (int cp : normalized.codePoints().toArray()) {
                node = node.children.computeIfAbsent(cp, k -> new Node());
            }
            if (!node.end) {
                node.end = true;
                count++;
            }
        }
        size = count;
    }

    /**
     * 词数。
     *
     * @return 归一化后不重复的词数
     */
    public int size() {
        return size;
    }

    /**
     * 查找文本中的第一个敏感词。
     *
     * @param text 文本，可为 null
     * @return 命中的词（归一化后的形式）；没有命中时为 null
     */
    public String find(String text) {
        if (text == null || size == 0) {
            return null;
        }
        int[] cps = normalize(text).codePoints().toArray();
        for (int start = 0; start < cps.length; start++) {
            Node node = root;
            for (int i = start; i < cps.length; i++) {
                node = node.children.get(cps[i]);
                if (node == null) {
                    break;
                }
                if (node.end) {
                    return new String(cps, start, i - start + 1);
                }
            }
        }
        return null;
    }

    /**
     * 归一化：转小写，去掉空白、标点和符号。
     *
     * @param text 文本
     * @return 归一化后的文本
     */
    static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (!isIgnorable(cp)) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    /**
     * 是否为匹配时忽略的字符：空白、标点、符号。
     *
     * @param cp 码点
     * @return 忽略时为 true
     */
    private static boolean isIgnorable(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }
}
