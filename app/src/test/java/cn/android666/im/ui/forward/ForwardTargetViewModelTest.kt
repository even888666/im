package cn.android666.im.ui.forward

import app.cash.turbine.test
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.ConversationRepository
import cn.android666.im.testing.MainDispatcherRule
import cn.android666.im.testing.OrgTestData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [ForwardTargetViewModel] 测试：最近聊天（文件传输助手在最前，不含群聊）、搜索同事 */
@RunWith(RobolectricTestRunner::class)
class ForwardTargetViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val data = OrgTestData()

    @After
    fun tearDown() = data.close()

    @Test
    fun recentAndSearch() = runTest(mainDispatcherRule.testDispatcher) {
        data.seed()
        val db = data.holder.require()
        db.conversationDao().upsert(ConversationEntity("10002@im.example.com", ConversationEntity.TYPE_SINGLE, draft = "x"))
        db.conversationDao().upsert(ConversationEntity("g1@conference.im.example.com", ConversationEntity.TYPE_GROUP, draft = "x"))
        db.conversationDao().upsert(ConversationEntity("10001@im.example.com", ConversationEntity.TYPE_SELF, draft = "x"))
        val vm = ForwardTargetViewModel(ConversationRepository(data.holder), data.repository, "10001@im.example.com")

        vm.targets.test {
            var list = awaitItem()
            while (list.size < 2) list = awaitItem()
            assertThat(list.map { it.id }).containsExactly("10001@im.example.com", "10002@im.example.com").inOrder()
            assertThat(list[0].kind).isEqualTo(ConversationKind.SELF)
            assertThat(list[1].name).isEqualTo("李四")

            vm.onQueryChanged("wangwu")
            var result = awaitItem()
            while (result.firstOrNull()?.id != "10003@im.example.com") result = awaitItem()
            assertThat(result.single().name).isEqualTo("王五")

            // 搜到自己：作为文件传输助手
            vm.onQueryChanged("zhangsan")
            result = awaitItem()
            while (result.firstOrNull()?.id != "10001@im.example.com") result = awaitItem()
            assertThat(result.single().kind).isEqualTo(ConversationKind.SELF)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
