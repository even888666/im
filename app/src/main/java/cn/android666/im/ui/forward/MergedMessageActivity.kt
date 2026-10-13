package cn.android666.im.ui.forward

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.extension.msg.ForwardedItem
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.message.MessageJson
import cn.android666.im.databinding.ActivityMergedMessageBinding
import cn.android666.im.databinding.ItemMergedMessageBinding
import cn.android666.im.ui.base.appContainer
import cn.android666.im.ui.base.applySystemBarsPadding
import cn.android666.im.ui.chat.ChatAdapter
import cn.android666.im.ui.chat.ChatTimeline
import kotlinx.coroutines.launch

/**
 * 合并转发的聊天记录（开发计划 4.16，协议规范 §7.7）：按时间顺序列出其中的消息；其中又有合并转发时点击继续查看。
 * 合并转发中的消息不能再撤回、引用（只读）。
 */
class MergedMessageActivity : AppCompatActivity() {

    private val log = Logs.file(TAG)

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivityMergedMessageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarsPadding(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val content = MessageJson.decodeContent(MERGED_TYPE, intent.getStringExtra(EXTRA_CONTENT))
        val merged = content as? MessageContent.Merged
        if (merged == null) {
            log.warn("不是合并转发的内容：{}", content)
            finish()
            return
        }
        if (merged.title.isNotEmpty()) binding.toolbar.title = merged.title
        binding.list.layoutManager = LinearLayoutManager(this)
        // 先用工号显示，查到组织架构中的姓名后刷新
        val adapter = ItemAdapter(merged.items)
        binding.list.adapter = adapter
        lifecycleScope.launch {
            val org = appContainer.orgRepository
            adapter.names = merged.items.map { it.from.substringBefore('/') }.distinct()
                .associateWith { jid -> org.employee(jid)?.name ?: jid.substringBefore('@') }
        }
    }

    /**
     * 消息列表。
     *
     * @property items 合并转发中的消息
     */
    private inner class ItemAdapter(private val items: List<ForwardedItem>) : RecyclerView.Adapter<ItemAdapter.Holder>() {

        /** 发送方 bare JID → 姓名；设置后刷新 */
        var names: Map<String, String> = emptyMap()
            set(value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }

        /**
         * 列表项。
         *
         * @property binding 布局
         */
        inner class Holder(val binding: ItemMergedMessageBinding) : RecyclerView.ViewHolder(binding.root)

        /**
         * 创建列表项。
         *
         * @param parent 父容器
         * @param viewType 类型（只有一种）
         * @return 列表项
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemMergedMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        /**
         * 条数。
         *
         * @return 条数
         */
        override fun getItemCount(): Int = items.size

        /**
         * 绑定数据。
         *
         * @param holder 列表项
         * @param position 位置
         */
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val jid = item.from.substringBefore('/')
            val name = names[jid] ?: jid.substringBefore('@')
            val b = holder.binding
            b.avatar.bind(name, jid, "")
            b.name.text = name
            b.time.text = item.stamp?.let { ChatAdapter.timeHeaderText(this@MergedMessageActivity, ChatTimeline.label(it, System.currentTimeMillis())) }
            b.body.text = item.body
            val nested = item.content as? MessageContent.Merged
            if (nested != null) {
                b.root.setOnClickListener { start(this@MergedMessageActivity, nested) }
            } else {
                b.root.setOnClickListener(null)
                b.root.isClickable = false
            }
        }
    }

    companion object {
        private const val TAG = "MergedMessage"
        private const val EXTRA_CONTENT = "content"

        /** 合并转发的类型名（JSON 损坏时的兜底类型） */
        private const val MERGED_TYPE = "merged"

        /**
         * 查看合并转发的聊天记录。
         *
         * @param context Context
         * @param content 合并转发的内容
         */
        fun start(context: Context, content: MessageContent.Merged) {
            context.startActivity(
                Intent(context, MergedMessageActivity::class.java).putExtra(EXTRA_CONTENT, MessageJson.encodeContent(content)),
            )
        }
    }
}
