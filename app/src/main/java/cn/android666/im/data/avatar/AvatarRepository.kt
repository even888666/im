package cn.android666.im.data.avatar

import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.log.Logs
import cn.android666.im.core.media.AvatarImage
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 头像（开发计划 3.21）：组织架构中每个员工带有头像的 SHA-1（avatarHash，协议规范 §4.1），
 * 本地按哈希缓存头像文件，哈希变化（换了头像）时才重新从 vCard 下载。
 *
 * - 缓存目录：`{filesDir}/avatars/{哈希}.jpg`，所有账号共用（哈希相同即图片相同）；
 * - 同一个哈希同时只下载一次；没有头像的哈希记在内存中，不重复请求；
 * - 设置本人头像：处理成 256 像素的正方形 JPEG 后写入 vCard，并直接放入缓存。
 *
 * @property client XMPP 客户端
 * @property cacheDir 头像缓存目录
 * @property dispatchers 线程调度
 * @property scope 应用级协程作用域：下载在这里进行，某个界面取消等待不影响其他等待同一头像的界面
 */
class AvatarRepository(
    private val client: XmppClient,
    private val cacheDir: File,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
) {

    private val log = Logs.file(TAG)

    /** 正在下载的头像：哈希 → 下载任务 */
    private val inFlight = HashMap<String, Deferred<File?>>()

    /** 已确认没有头像（vCard 中没有图片）的哈希，避免反复请求 */
    private val missing = HashSet<String>()

    /**
     * 取得头像文件：已缓存时直接返回，否则下载。
     *
     * @param jid bare JID
     * @param avatarHash 组织架构中的头像哈希；为空表示没有头像
     * @return 头像文件；没有头像或下载失败时为 null（界面显示文字头像）
     */
    suspend fun file(jid: String, avatarHash: String): File? {
        if (avatarHash.isEmpty()) return null
        val cached = fileOf(avatarHash)
        if (cached.exists()) return cached
        val task = synchronized(inFlight) {
            if (avatarHash in missing) return null
            inFlight.getOrPut(avatarHash) {
                scope.async(dispatchers.io) { download(jid, avatarHash) }.also { deferred ->
                    deferred.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(avatarHash) } }
                }
            }
        }
        return task.await()
    }

    /**
     * 下载并保存头像。
     *
     * @param jid bare JID
     * @param avatarHash 头像哈希（作为文件名）
     * @return 头像文件；没有头像或失败时为 null
     */
    private suspend fun download(jid: String, avatarHash: String): File? {
        val data = try {
            client.loadAvatar(jid)
        } catch (e: XmppException) {
            log.info("下载头像失败：{}，{}", jid, e.message)
            return null
        }
        if (data == null) {
            log.info("{} 的 vCard 中没有头像（组织架构中的哈希 {} 可能已过时）", jid, avatarHash)
            synchronized(inFlight) { missing += avatarHash }
            return null
        }
        val actual = sha1(data)
        if (actual != avatarHash) {
            // 组织架构还没同步到最新的哈希；仍按请求的哈希保存，同步后会按新哈希重新下载
            log.info("{} 的头像哈希与组织架构不一致：{} / {}", jid, actual, avatarHash)
        }
        return save(avatarHash, data)
    }

    /**
     * 设置本人头像。
     *
     * @param image 选择的原图数据
     * @return 新头像的哈希
     * @throws IOException 图片无法解码
     * @throws XmppException 上传失败
     */
    suspend fun setMyAvatar(image: ByteArray): String {
        val jpeg = withContext(dispatchers.default) { AvatarImage.toAvatarJpeg(image) }
        client.setMyAvatar(jpeg, AvatarImage.MIME_TYPE)
        val hash = sha1(jpeg)
        withContext(dispatchers.io) { save(hash, jpeg) }
        log.info("本人头像已更新：{}，{} 字节", hash, jpeg.size)
        return hash
    }

    /**
     * 写入缓存文件（先写临时文件再改名，避免读到写了一半的文件）。
     *
     * @param hash 哈希
     * @param data 图片数据
     * @return 缓存文件
     */
    private fun save(hash: String, data: ByteArray): File {
        cacheDir.mkdirs()
        val target = fileOf(hash)
        val temp = File(cacheDir, "$hash.tmp")
        temp.writeBytes(data)
        if (!temp.renameTo(target)) {
            target.writeBytes(data)
            temp.delete()
        }
        return target
    }

    /**
     * 哈希对应的缓存文件。
     *
     * @param hash 哈希
     * @return 文件（不一定存在）
     */
    private fun fileOf(hash: String): File = File(cacheDir, "$hash.jpg")

    companion object {
        private const val TAG = "AvatarRepository"

        /**
         * 计算 SHA-1（与 XEP-0153、服务端的 avatarHash 一致：小写十六进制）。
         *
         * @param data 数据
         * @return 哈希
         */
        fun sha1(data: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }
    }
}
