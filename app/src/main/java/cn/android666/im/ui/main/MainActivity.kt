package cn.android666.im.ui.main

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.databinding.ActivityMainBinding
import cn.android666.im.di.AppContainer
import cn.android666.im.ui.base.MessageDialogFragment
import cn.android666.im.ui.base.appViewModels
import cn.android666.im.ui.base.applySystemBarsPadding
import cn.android666.im.ui.base.collectWhenStarted
import cn.android666.im.ui.base.startAsNewTask
import cn.android666.im.ui.login.LoginActivity
import cn.android666.im.ui.password.ChangePasswordActivity

/**
 * 主界面（开发计划 2.21 ~ 2.23）：底部 Tab（消息 / 通讯录 / 我）、顶部连接状态条、
 * 被挤下线 / 账号禁用的提示、退出登录后回到登录页。
 */
class MainActivity : AppCompatActivity() {

    private val log = Logs.file(TAG)

    private val viewModel by appViewModels(::createViewModel)

    private lateinit var binding: ActivityMainBinding

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 底部导航栏由 BottomNavigationView 自己处理
        applySystemBarsPadding(binding.root, bottom = false)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        binding.bottomNav.setOnItemReselectedListener { /* 重复点击当前 Tab 不处理 */ }
        if (savedInstanceState == null) {
            showTab(R.id.tab_messages)
        } else {
            // 页面重建：Fragment 和选中的 Tab 都已由系统恢复，只需恢复标题
            updateTitle(binding.bottomNav.selectedItemId)
        }

        // 返回键：退到后台而不是关闭主界面（IM 需要常驻，回来时不用重新加载）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        // 会话被结束的提示框点击"确定"后回到登录页
        supportFragmentManager.setFragmentResultListener(REQUEST_SESSION_ENDED, this) { _, _ ->
            startAsNewTask(LoginActivity::class.java)
        }

        collectWhenStarted(viewModel.banner) { text ->
            binding.bannerText.isVisible = text != null
            text?.let(binding.bannerText::setText)
        }
        collectWhenStarted(viewModel.events, ::onEvent)
    }

    /**
     * 处理主界面事件。
     *
     * @param event 事件
     */
    private fun onEvent(event: MainEvent) {
        log.info("主界面事件：{}", event)
        when (event) {
            MainEvent.GoChangePassword -> startAsNewTask(ChangePasswordActivity::class.java)
            MainEvent.GoLogin -> startAsNewTask(LoginActivity::class.java)
            is MainEvent.SessionEnded -> MessageDialogFragment.show(
                fragmentManager = supportFragmentManager,
                requestKey = REQUEST_SESSION_ENDED,
                title = R.string.session_ended_title,
                message = event.message,
                positive = R.string.ok,
            )
        }
    }

    /**
     * 切换到指定 Tab：已创建的 Fragment 只做显示 / 隐藏，保留各自的状态（滚动位置等）。
     *
     * @param itemId 底部菜单项 id
     */
    private fun showTab(@IdRes itemId: Int) {
        val tag = TAB_TAG_PREFIX + itemId
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction().setReorderingAllowed(true)
        fm.fragments
            .filter { it.tag?.startsWith(TAB_TAG_PREFIX) == true && it.tag != tag }
            .forEach { transaction.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing == null) {
            transaction.add(R.id.content, createTab(itemId), tag)
        } else {
            transaction.show(existing)
        }
        transaction.commit()
        updateTitle(itemId)
    }

    /**
     * 创建 Tab 对应的 Fragment。
     *
     * @param itemId 底部菜单项 id
     * @return Tab 页面
     */
    private fun createTab(@IdRes itemId: Int): Fragment = when (itemId) {
        R.id.tab_messages -> PlaceholderFragment.newInstance(R.string.messages_empty)
        R.id.tab_contacts -> PlaceholderFragment.newInstance(R.string.contacts_placeholder)
        R.id.tab_me -> MeFragment()
        else -> error("未知的 Tab：$itemId")
    }

    /**
     * 标题栏显示当前 Tab 的名称。
     *
     * @param itemId 底部菜单项 id
     */
    private fun updateTitle(@IdRes itemId: Int) {
        binding.toolbar.title = binding.bottomNav.menu.findItem(itemId)?.title
    }

    companion object {
        private const val TAG = "MainActivity"

        /** Tab Fragment 的 tag 前缀，后接菜单项 id */
        private const val TAB_TAG_PREFIX = "tab_"

        /** 会话被结束提示框的结果 key */
        private const val REQUEST_SESSION_ENDED = "sessionEnded"

        /**
         * 创建主界面 ViewModel（Activity 和其中的 Fragment 共用）。
         *
         * @param container 应用级依赖容器
         * @return 主界面 ViewModel
         */
        fun createViewModel(container: AppContainer): MainViewModel = MainViewModel(
            sessionManager = container.sessionManager,
            connectionState = container.xmppClient.connectionState,
            networkStatus = container.networkMonitor.status,
        )
    }
}
