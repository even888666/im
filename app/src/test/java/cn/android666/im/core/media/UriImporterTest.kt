package cn.android666.im.core.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** [UriImporter] 与 [GallerySaver.uniqueFile] 测试 */
@RunWith(RobolectricTestRunner::class)
class UriImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun import_copiesContent() = runTest {
        val uri = Uri.parse("content://cn.android666.test/images/1")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        val dir = temp.newFolder("import")

        val imported = UriImporter(context, dir, Dispatchers.Unconfined).import(uri)
        assertThat(imported.file.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(imported.file.parentFile).isEqualTo(dir)
    }

    /** file:// 地址：文件名取路径最后一段 */
    @Test
    fun import_fileUri_nameFromPath() = runTest {
        val source = temp.newFile("IMG_001.jpg").apply { writeBytes(byteArrayOf(9)) }
        val imported = UriImporter(context, temp.newFolder("import"), Dispatchers.Unconfined).import(Uri.fromFile(source))
        assertThat(imported.displayName).isEqualTo("IMG_001.jpg")
        assertThat(imported.file.readBytes()).isEqualTo(byteArrayOf(9))
    }

    @Test
    fun import_missingFile_throwsAndLeavesNothing() = runTest {
        val dir = temp.newFolder("import")
        val error = runCatching {
            UriImporter(context, dir, Dispatchers.Unconfined).import(Uri.fromFile(File(temp.root, "none.jpg")))
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(dir.listFiles()).isEmpty()
    }

    @Test
    fun uniqueFile_appendsNumber() {
        val dir = temp.newFolder("album")
        assertThat(GallerySaver.uniqueFile(dir, "a.jpg").name).isEqualTo("a.jpg")
        File(dir, "a.jpg").createNewFile()
        assertThat(GallerySaver.uniqueFile(dir, "a.jpg").name).isEqualTo("a(1).jpg")
        File(dir, "a(1).jpg").createNewFile()
        assertThat(GallerySaver.uniqueFile(dir, "a.jpg").name).isEqualTo("a(2).jpg")
        assertThat(GallerySaver.uniqueFile(dir, "noext").name).isEqualTo("noext")
    }
}
