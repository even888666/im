package cn.android666.im.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.ui.base.appContainer
import coil3.load
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 头像控件（开发计划 3.21）：底层是文字头像（[NameAvatar]），有头像图片时在上面显示圆形图片；
 * 图片按 avatarHash 从本地缓存读取，没有缓存时后台下载（[cn.android666.im.data.avatar.AvatarRepository]）。
 *
 * 在 RecyclerView 中复用时，每次 [bind] 会取消上一次未完成的加载，避免图片错位。
 *
 * XML 属性 `app:avatarTextSize` 设置文字头像的字号。
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 文字头像 */
    private val textView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(context.getColor(android.R.color.white))
    }

    /** 头像图片 */
    private val imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    /** 当前的加载任务 */
    private var loadJob: Job? = null

    /** 当前绑定的头像哈希（加载完成时校验，防止显示到复用后的另一个人） */
    private var boundHash: String = ""

    /** 最近一次绑定的姓名和 JID（附加到窗口后补加载用） */
    private var boundName: String = ""
    private var boundJid: String = ""

    init {
        val typed = context.obtainStyledAttributes(attrs, R.styleable.AvatarView)
        val textSize = typed.getDimension(R.styleable.AvatarView_avatarTextSize, resources.displayMetrics.scaledDensity * 14)
        typed.recycle()
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * 显示某人的头像。
     *
     * @param name 姓名（文字头像）
     * @param jid bare JID（文字头像配色、下载头像）
     * @param avatarHash 头像哈希；为空时只显示文字头像
     */
    fun bind(name: String, jid: String, avatarHash: String) {
        boundName = name
        boundJid = jid
        NameAvatar.bind(textView, name, jid)
        loadJob?.cancel()
        boundHash = avatarHash
        imageView.setImageDrawable(null)
        if (avatarHash.isEmpty()) return
        val owner = findViewTreeLifecycleOwner() ?: return
        loadJob = owner.lifecycleScope.launch {
            val file = try {
                context.appContainer.avatarRepository.file(jid, avatarHash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("加载头像失败：{}", jid, e)
                null
            }
            if (file != null && boundHash == avatarHash) {
                imageView.load(file) { transformations(CircleCropTransformation()) }
            }
        }
    }

    /** 文字头像上显示的文字（测试用） */
    val displayedText: CharSequence get() = textView.text

    /** 未附加到窗口时找不到 LifecycleOwner，附加后补加载一次 */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (boundHash.isNotEmpty() && imageView.drawable == null && loadJob?.isActive != true) {
            bind(boundName, boundJid, boundHash)
        }
    }

    private companion object {
        private val log = Logs.file("AvatarView")
    }
}
