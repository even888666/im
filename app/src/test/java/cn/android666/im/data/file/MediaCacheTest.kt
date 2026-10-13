package cn.android666.im.data.file

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [MediaCache] 测试：按地址命名、按最近使用时间清理 */
class MediaCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val cache by lazy { MediaCache(File(tempFolder.root, "media").apply { mkdirs() }) }

    @Test
    fun fileFor_stableAndExtension() {
        val a = cache.fileFor("https://h/httpfileupload/s1/a.JPG")
        assertThat(a).isEqualTo(cache.fileFor("https://h/httpfileupload/s1/a.JPG"))
        assertThat(a.name).endsWith(".jpg")
        assertThat(cache.fileFor("https://h/httpfileupload/s2/a.JPG")).isNotEqualTo(a)
        // 用原始文件名的扩展名
        assertThat(cache.fileFor("https://h/x", "季度报告.pdf").name).endsWith(".pdf")
        assertThat(cache.fileFor("https://h/x", "无扩展名").name).doesNotContain(".")
    }

    @Test
    fun get_onlyExisting() {
        val url = "https://h/s/a.png"
        assertThat(cache.get(url)).isNull()
        cache.fileFor(url).writeText("x")
        assertThat(cache.get(url)).isEqualTo(cache.fileFor(url))
    }

    @Test
    fun trimTo_deletesOldestFirst_ignoresPart() {
        val files = (1..4).map { i ->
            cache.fileFor("https://h/$i.bin").apply {
                writeBytes(ByteArray(100))
                setLastModified(1_000_000_000_000L + i * 1000)
            }
        }
        File(files[0].path + FileDownloader.PART_SUFFIX).writeBytes(ByteArray(1000))
        assertThat(cache.size()).isEqualTo(400)

        assertThat(cache.trimTo(250)).isEqualTo(2)

        assertThat(files.map { it.exists() }).containsExactly(false, false, true, true).inOrder()
        assertThat(cache.trimTo(1000)).isEqualTo(0)

        cache.clear()
        assertThat(tempFolder.root.resolve("media").listFiles()).isEmpty()
    }
}
