package cn.android666.im.data.file

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [FileTypes] 测试：类型、大小显示、安全文件名 */
class FileTypesTest {

    @Test
    fun category_byExtensionThenMime() {
        assertThat(FileTypes.category("季度报告.PDF", null)).isEqualTo(FileCategory.PDF)
        assertThat(FileTypes.category("a.docx", null)).isEqualTo(FileCategory.WORD)
        assertThat(FileTypes.category("a.xlsx", "application/octet-stream")).isEqualTo(FileCategory.EXCEL)
        assertThat(FileTypes.category("a.pptx", null)).isEqualTo(FileCategory.POWERPOINT)
        assertThat(FileTypes.category("a.7z", null)).isEqualTo(FileCategory.ARCHIVE)
        assertThat(FileTypes.category("a.apk", null)).isEqualTo(FileCategory.APK)
        assertThat(FileTypes.category("noext", "image/png")).isEqualTo(FileCategory.IMAGE)
        assertThat(FileTypes.category("noext", "video/mp4")).isEqualTo(FileCategory.VIDEO)
        assertThat(FileTypes.category("noext", "application/pdf")).isEqualTo(FileCategory.PDF)
        assertThat(FileTypes.category("a.unknown", null)).isEqualTo(FileCategory.OTHER)
    }

    @Test
    fun extension() {
        assertThat(FileTypes.extension("a.tar.GZ")).isEqualTo("gz")
        assertThat(FileTypes.extension(".hidden")).isEmpty()
        assertThat(FileTypes.extension("a.")).isEmpty()
        assertThat(FileTypes.extension("a")).isEmpty()
    }

    @Test
    fun formatSize() {
        assertThat(FileTypes.formatSize(0)).isEqualTo("0 B")
        assertThat(FileTypes.formatSize(1023)).isEqualTo("1023 B")
        assertThat(FileTypes.formatSize(1024)).isEqualTo("1 KB")
        assertThat(FileTypes.formatSize(12 * 1024 + 600)).isEqualTo("13 KB")
        assertThat(FileTypes.formatSize(1_258_291)).isEqualTo("1.2 MB")
        assertThat(FileTypes.formatSize(100L * 1024 * 1024)).isEqualTo("100.0 MB")
        assertThat(FileTypes.formatSize(1_127_428_915)).isEqualTo("1.05 GB")
    }

    @Test
    fun safeName_removesPathAndControlChars() {
        assertThat(FileTypes.safeName("../../etc/passwd")).isEqualTo(".._.._etc_passwd")
        assertThat(FileTypes.safeName("a\\b\nc.txt")).isEqualTo("a_b_c.txt")
        assertThat(FileTypes.safeName("..")).isEqualTo("未命名")
        assertThat(FileTypes.safeName("  ")).isEqualTo("未命名")
        assertThat(FileTypes.safeName("季度报告.pdf")).isEqualTo("季度报告.pdf")
    }

    @Test
    fun safeName_longNameKeepsExtension() {
        val name = "很".repeat(150) + ".docx"
        val safe = FileTypes.safeName(name)
        assertThat(safe.length).isEqualTo(100)
        assertThat(safe).endsWith(".docx")
    }
}
