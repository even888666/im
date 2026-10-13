package cn.android666.im.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.ui.forward.ForwardTargetActivity
import cn.android666.im.ui.forward.MergedMessageActivity
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
                    titleFormatter = object : MergedTitleFormatter {
                        override fun pair(self: String, peer: String) = getString(R.string.forward_merged_title, self, peer)
                        override fun selfOnly(self: String) = getString(R.string.forward_merged_title_self, self)
                    },
                )
            }
        }
    }

    private lateinit var binding: ActivityChatBinding

    /** 正在把草稿恢复到输入框（此时的文字变化不是用户输入） */
    private var restoringDraft = false

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
        collectWhenStarted(viewModel.peerName) { if (viewModel.selection.value == null) binding.toolbar.title = title(it) }

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
            onToggle = { viewModel.toggleSelection(it.rowId) },
            onOpen = { message -> (message.content as? MessageContent.Merged)?.let { MergedMessageActivity.start(this, it) } },
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
                MessageActionsDialogFragment.ACTION_SELECT -> viewModel.startSelection(result.getLong(MessageActionsDialogFragment.RESULT_ROW_ID))
                MessageActionsDialogFragment.ACTION_FORWARD -> pickForwardTarget(setOf(result.getLong(MessageActionsDialogFragment.RESULT_ROW_ID)), merged = false)
                MessageActionsDialogFragment.ACTION_DELETE -> confirmDelete(setOf(result.getLong(MessageActionsDialogFragment.RESULT_ROW_ID)))
            }
        }

        binding.input.doAfterTextChanged {
            binding.send.isEnabled = !it.isNullOrBlank()
            // 恢复草稿不是用户在输入，不发送"正在输入"
            if (!restoringDraft) viewModel.onInputChanged(it?.toString().orEmpty())
        }
        // 第一次打开时恢复草稿（页面重建时输入框内容由系统恢复）
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                val draft = viewModel.loadDraft()
                if (draft.isNotEmpty() && binding.input.text.isNullOrEmpty()) {
                    restoringDraft = true
                    binding.input.setText(draft)
                    binding.input.setSelection(draft.length)
                    restoringDraft = false
                }
            }
        }
        // 多选模式：标题显示已选条数，操作栏代替输入栏，返回键退出多选
        val exitSelection = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = viewModel.cancelSelection()
        }
        onBackPressedDispatcher.addCallback(this, exitSelection)
        binding.deleteSelected.setOnClickListener { viewModel.selection.value?.takeIf { it.isNotEmpty() }?.let(::confirmDelete) }
        // 转发（开发计划 4.16）：先选择目标
        binding.forwardEach.setOnClickListener { viewModel.selection.value?.takeIf { it.isNotEmpty() }?.let { pickForwardTarget(it, merged = false) } }
        binding.forwardMerged.setOnClickListener { viewModel.selection.value?.takeIf { it.isNotEmpty() }?.let { pickForwardTarget(it, merged = true) } }
        supportFragmentManager.setFragmentResultListener(REQUEST_DELETE, this) { _, result ->
            val ids = viewModel.pendingDelete
            viewModel.pendingDelete = null
            if (ids != null && result.getBoolean(MessageDialogFragment.RESULT_POSITIVE)) viewModel.delete(ids)
        }
        collectWhenStarted(viewModel.selection) { selected ->
            adapter.selection = selected
            exitSelection.isEnabled = selected != null
            binding.selectionBar.isVisible = selected != null
            binding.inputBar.isVisible = selected == null
            binding.deleteSelected.isEnabled = !selected.isNullOrEmpty()
            binding.forwardEach.isEnabled = !selected.isNullOrEmpty()
            binding.forwardMerged.isEnabled = !selected.isNullOrEmpty()
            binding.toolbar.title = if (selected != null) getString(R.string.chat_selected_count, selected.size) else title(viewModel.peerName.value)
            if (selected != null) binding.quoteBar.isVisible = false
        }

        // 引用条
        binding.quoteClose.setOnClickListener { viewModel.cancelReply() }
        collectWhenStarted(viewModel.reply) { draft ->
            binding.quoteBar.isVisible = draft != null && viewModel.selection.value == null
            if (draft != null) {
                val name = draft.senderName ?: getString(R.string.chat_quote_self)
                binding.quoteText.text = getString(R.string.chat_quote_format, name, draft.text)
                binding.input.requestFocus()
            }
        }
        collectWhenStarted(viewModel.watermark) { binding.watermark.text = it }
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

    /** 选择转发对象；返回后转发 [ChatViewModel.pendingForward] 中的消息 */
    private val forwardTarget = registerForActivityResult(ForwardTargetActivity.Contract()) { target ->
        val pending = viewModel.pendingForward
        viewModel.pendingForward = null
        if (target != null && pending != null) viewModel.forward(target, pending.first, pending.second)
    }

    /**
     * 打开选择转发对象的页面。
     *
     * @param rowIds 被转发的消息
     * @param merged 是否合并转发
     */
    private fun pickForwardTarget(rowIds: Set<Long>, merged: Boolean) {
        viewModel.pendingForward = rowIds to merged
        forwardTarget.launch(Unit)
    }

    /**
     * 删除前确认（只删除本机的消息）。
     *
     * @param rowIds 消息 rowId
     */
    private fun confirmDelete(rowIds: Set<Long>) {
        viewModel.pendingDelete = rowIds
        MessageDialogFragment.show(
            fragmentManager = supportFragmentManager,
            requestKey = REQUEST_DELETE,
            title = 0,
            message = R.string.chat_delete_confirm,
            positive = R.string.chat_action_delete,
            negative = R.string.cancel,
            cancelable = true,
        )
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

    /** 页面不可见：保存草稿 */
    override fun onStop() {
        viewModel.saveDraft(binding.input.text?.toString().orEmpty())
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

        /** 删除确认框的结果 key */
        private const val REQUEST_DELETE = "deleteMessages"

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
