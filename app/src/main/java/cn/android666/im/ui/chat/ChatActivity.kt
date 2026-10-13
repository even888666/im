package cn.android666.im.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.databinding.ActivityChatBinding
import cn.android666.im.ui.base.MessageDialogFragment
import cn.android666.im.ui.base.appContainer
import cn.android666.im.ui.base.applySystemBarsPadding
import cn.android666.im.ui.base.collectWhenStarted
import kotlinx.coroutines.launch

/**
 * 聊天页（开发计划 4.8）：单聊、文件传输助手（群聊见 M6）。
 *
 * 列表倒序显示（最新在底部），向上滑动自动加载更早的消息；新消息到达时如果停留在底部则自动滚动。
 * 页面可见期间该会话不计未读。
 */
class ChatActivity : AppCompatActivity() {

    private val log = Logs.file(TAG)

    private val viewModel by viewModels<ChatViewModel> {
        viewModelFactory {
            initializer {
                val container = appContainer
                val kind = intent.getStringExtra(EXTRA_KIND)?.let(ConversationKind::valueOf) ?: ConversationKind.SINGLE
                ChatViewModel(
                    conversationId = requireNotNull(intent.getStringExtra(EXTRA_ID)) { "缺少会话 ID" },
                    kind = kind,
                    messages = container.messageRepository,
                    conversations = container.conversationRepository,
                    readMarkers = container.readMarkers,
                    retractions = container.retractions,
                    org = container.orgRepository,
                    history = container.historyLoader,
                    chatStates = container.chatStates,
                )
            }
        }
    }

    private lateinit var binding: ActivityChatBinding

    /** 等待用户确认重发的消息 rowId（确认框在页面重建后仍在，但 Activity 字段会丢失，丢失时忽略结果） */
    private var pendingResendRowId: Long? = null

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarsPadding(binding.root)
        pendingResendRowId = savedInstanceState?.getLong(STATE_RESEND, -1)?.takeIf { it > 0 }
        log.info("打开聊天页：{}（{}）", viewModel.conversationId, viewModel.kind)

        binding.toolbar.setNavigationOnClickListener { finish() }
        collectWhenStarted(viewModel.peerName) { binding.toolbar.title = title(it) }

        val adapter = ChatAdapter(
            showReadState = viewModel.kind == ConversationKind.SINGLE,
            now = System::currentTimeMillis,
            onResend = { message ->
                pendingResendRowId = message.rowId
                MessageDialogFragment.show(
                    fragmentManager = supportFragmentManager,
                    requestKey = REQUEST_RESEND,
                    title = 0,
                    message = R.string.chat_resend_confirm,
                    positive = R.string.chat_resend,
                    negative = R.string.cancel,
                    cancelable = true,
                )
            },
            onLongClick = { message ->
                MessageActionsDialogFragment.show(supportFragmentManager, message.rowId, message.body, viewModel.actionsFor(message))
            },
            selfJid = appContainer.selfJid(),
        )
        val layoutManager = LinearLayoutManager(this).apply { reverseLayout = true }
        binding.list.layoutManager = layoutManager
        binding.list.adapter = adapter
        // 新消息插入到列表开头（底部）：停留在底部附近时滚动显示
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0 && layoutManager.findFirstVisibleItemPosition() <= 1) binding.list.scrollToPosition(0)
            }
        })
        lifecycleScope.launch { viewModel.items.collect(adapter::submitData) }
        // 从服务器加载更早的历史失败（如断网）后，再次滑到顶部时重试
        var appendError = false
        adapter.addLoadStateListener { states -> appendError = states.mediator?.append is LoadState.Error }
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && appendError && !recyclerView.canScrollVertically(-1)) {
                    log.info("重试加载更早的历史")
                    adapter.retry()
                }
            }
        })

        supportFragmentManager.setFragmentResultListener(REQUEST_RESEND, this) { _, result ->
            val rowId = pendingResendRowId
            pendingResendRowId = null
            if (rowId != null && result.getBoolean(MessageDialogFragment.RESULT_POSITIVE)) viewModel.resend(rowId)
        }

        supportFragmentManager.setFragmentResultListener(MessageActionsDialogFragment.REQUEST_KEY, this) { _, result ->
            when (result.getString(MessageActionsDialogFragment.RESULT_ACTION)) {
                MessageActionsDialogFragment.ACTION_COPY -> copy(result.getString(MessageActionsDialogFragment.RESULT_TEXT).orEmpty())
                MessageActionsDialogFragment.ACTION_RETRACT -> viewModel.retract(result.getLong(MessageActionsDialogFragment.RESULT_ROW_ID))
                MessageActionsDialogFragment.ACTION_REPLY -> viewModel.startReply(result.getLong(MessageActionsDialogFragment.RESULT_ROW_ID))
            }
        }

        binding.input.doAfterTextChanged {
            binding.send.isEnabled = !it.isNullOrBlank()
            viewModel.onInputChanged(it?.toString().orEmpty())
        }
        // 引用条
        binding.quoteClose.setOnClickListener { viewModel.cancelReply() }
        collectWhenStarted(viewModel.reply) { draft ->
            binding.quoteBar.isVisible = draft != null
            if (draft != null) {
                val name = draft.senderName ?: getString(R.string.chat_quote_self)
                binding.quoteText.text = getString(R.string.chat_quote_format, name, draft.text)
                binding.input.requestFocus()
            }
        }
        collectWhenStarted(viewModel.peerTyping) { typing ->
            binding.toolbar.subtitle = if (typing) getString(R.string.chat_peer_typing) else null
        }
        binding.send.setOnClickListener { viewModel.send(binding.input.text.toString()) }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                ChatEvent.Sent -> {
                    binding.input.text = null
                    binding.list.scrollToPosition(0)
                }
                is ChatEvent.Toast -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 复制消息文字到剪贴板。
     *
     * @param text 文字
     */
    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
        // Android 13 起系统会自己提示"已复制"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.chat_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * 标题：单聊为对方姓名（找不到时为工号），文件传输助手、公告为固定名称。
     *
     * @param peerName 对方姓名
     * @return 标题
     */
    private fun title(peerName: String?): String = when (viewModel.kind) {
        ConversationKind.SELF -> getString(R.string.conversation_self)
        ConversationKind.NOTICE -> getString(R.string.conversation_notice)
        else -> peerName ?: viewModel.conversationId.substringBefore('@')
    }

    /** 页面可见：该会话不计未读，并清除未读 */
    override fun onStart() {
        super.onStart()
        viewModel.onVisible()
    }

    /** 页面不可见 */
    override fun onStop() {
        viewModel.onHidden()
        super.onStop()
    }

    /**
     * 保存等待确认重发的消息。
     *
     * @param outState 保存的状态
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingResendRowId?.let { outState.putLong(STATE_RESEND, it) }
    }

    companion object {
        private const val TAG = "ChatActivity"
        private const val EXTRA_ID = "conversationId"
        private const val EXTRA_KIND = "kind"
        private const val STATE_RESEND = "pendingResend"

        /** 重发确认框的结果 key */
        private const val REQUEST_RESEND = "resend"

        /**
         * 创建打开聊天页的 Intent（通知点击等场景也使用）。
         *
         * @param context Context
         * @param conversationId 会话 ID（对方 bare JID / 本人 bare JID）
         * @param kind 会话类型
         * @return Intent
         */
        fun intent(context: Context, conversationId: String, kind: ConversationKind): Intent =
            Intent(context, ChatActivity::class.java).putExtra(EXTRA_ID, conversationId).putExtra(EXTRA_KIND, kind.name)

        /**
         * 打开聊天页。
         *
         * @param context Context
         * @param conversationId 会话 ID
         * @param kind 会话类型
         */
        fun start(context: Context, conversationId: String, kind: ConversationKind) {
            context.startActivity(intent(context, conversationId, kind))
        }
    }
}
