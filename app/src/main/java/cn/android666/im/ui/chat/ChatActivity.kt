package cn.android666.im.ui.chat

import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
import cn.android666.im.core.media.UriImporter
import cn.android666.im.data.message.ChatMessage
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.databinding.ActivityChatBinding
import cn.android666.im.ui.base.MessageDialogFragment
import cn.android666.im.ui.base.appContainer
import cn.android666.im.ui.base.applySystemBarsPadding
import cn.android666.im.ui.base.collectWhenStarted
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.ui.forward.ForwardTargetActivity
import cn.android666.im.ui.forward.MergedMessageActivity
import cn.android666.im.ui.media.ImagePreviewActivity
import cn.android666.im.ui.media.PreviewImage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                    media = container.mediaSender,
                    importer = UriImporter(applicationContext, File(cacheDir, IMPORT_DIR), container.dispatchers.io),
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
            onOpen = ::open,
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
                is ChatEvent.FileTooLarge ->
                    Toast.makeText(this, getString(R.string.chat_file_too_large, event.maxBytes / BYTES_PER_MB), Toast.LENGTH_SHORT).show()
            }
        }
        pendingPhoto = savedInstanceState?.getString(STATE_PHOTO)?.let(::File)
        binding.more.setOnClickListener { showMoreMenu() }
    }

    /** 拍照时照片的保存位置（相机应用返回前页面可能被销毁重建，保存在 savedInstanceState 中） */
    private var pendingPhoto: File? = null

    /** 从相册选择图片（最多 [MAX_PICK] 张） */
    private val pickImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) confirmSendImages(uris)
    }

    /** 拍照 */
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pendingPhoto
        pendingPhoto = null
        if (file == null) return@registerForActivityResult
        if (saved && file.length() > 0) {
            val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".jpg"
            viewModel.sendPhoto(file, name)
        } else {
            file.delete()
        }
    }

    /** 输入栏的"更多"菜单：相册、拍照 */
    private fun showMoreMenu() {
        PopupMenu(this, binding.more).apply {
            menu.add(0, MENU_ALBUM, 0, R.string.chat_pick_album)
            menu.add(0, MENU_CAMERA, 1, R.string.chat_take_photo)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ALBUM -> pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    MENU_CAMERA -> launchCamera()
                }
                true
            }
        }.show()
    }

    /** 打开相机应用拍照，照片写入缓存目录 */
    private fun launchCamera() {
        val dir = File(cacheDir, CAMERA_DIR).apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        try {
            takePhoto.launch(FileProvider.getUriForFile(this, "$packageName.files", file))
        } catch (e: ActivityNotFoundException) {
            log.warn("没有相机应用", e)
            pendingPhoto = null
            Toast.makeText(this, R.string.chat_no_camera, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 确认发送选中的图片，可选择"发送原图"。
     *
     * @param uris 图片
     */
    private fun confirmSendImages(uris: List<Uri>) {
        val original = booleanArrayOf(false)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.chat_send_images_title, uris.size))
            .setMultiChoiceItems(arrayOf(getString(R.string.chat_send_original)), original) { _, _, checked -> original[0] = checked }
            .setPositiveButton(R.string.chat_send) { _, _ -> viewModel.sendImages(uris, original[0]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 点击消息：合并转发打开聊天记录，图片打开大图预览。
     *
     * @param message 消息
     */
    private fun open(message: ChatMessage) {
        when (val content = message.content) {
            is MessageContent.Merged -> MergedMessageActivity.start(this, content)
            is MessageContent.Image -> startActivity(
                ImagePreviewActivity.intent(
                    this,
                    PreviewImage(
                        url = content.file.url,
                        name = content.file.name,
                        mime = content.file.mime,
                        sha256 = content.file.sha256,
                        localPath = message.media?.filePath,
                        thumbnail = message.media?.thumbnailPath?.takeIf { File(it).exists() }
                            ?: content.thumbnail?.url?.takeIf { it.isNotEmpty() },
                    ),
                ),
            )
            else -> Unit
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
        pendingPhoto?.let { outState.putString(STATE_PHOTO, it.path) }
    }

    companion object {
        private const val TAG = "ChatActivity"
        private const val EXTRA_ID = "conversationId"
        private const val EXTRA_KIND = "kind"
        private const val STATE_RESEND = "pendingResend"
        private const val STATE_PHOTO = "pendingPhoto"

        /** 从相册一次最多选择的图片数 */
        private const val MAX_PICK = 9

        /** "更多"菜单：相册 */
        private const val MENU_ALBUM = 1

        /** "更多"菜单：拍照 */
        private const val MENU_CAMERA = 2

        /** 导入的图片副本目录（缓存目录下） */
        private const val IMPORT_DIR = "import"

        /** 拍照的照片目录（缓存目录下，与 res/xml/file_paths.xml 一致） */
        private const val CAMERA_DIR = "camera"

        /** 1 MB */
        private const val BYTES_PER_MB = 1024 * 1024

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
