package cn.android666.im.data.contact

import app.cash.turbine.test
import cn.android666.im.core.database.entity.EmployeeEntity
import cn.android666.im.core.xmpp.RosterItem
import cn.android666.im.core.xmpp.RosterSubscription
import cn.android666.im.data.session.SessionContext
import cn.android666.im.testing.OrgTestData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [ContactRepository] 测试：获取花名册、推送、添加 / 移除，常用联系人只显示 to / both */
@RunWith(RobolectricTestRunner::class)
class ContactRepositoryTest {

    private val data = OrgTestData()

    @After
    fun tearDown() = data.close()

    @Test
    fun refresh_replacesAndFiltersContacts() = runTest {
        data.seed()
        val repository = ContactRepository(data.client, data.holder, backgroundScope)
        data.client.roster["10002@im.example.com"] = RosterItem("10002@im.example.com", RosterSubscription.BOTH)
        data.client.roster["10003@im.example.com"] = RosterItem("10003@im.example.com", RosterSubscription.FROM)
        data.client.roster["19999@im.example.com"] = RosterItem("19999@im.example.com", RosterSubscription.TO)

        assertThat(repository.refresh()).isEqualTo(2)

        val contacts = repository.observeContacts().first()
        // 有员工信息的按拼音排在前面，已离职（不在组织架构中）的排在最后
        assertThat(contacts.map { it.jid }).containsExactly("10002@im.example.com", "19999@im.example.com").inOrder()
        assertThat(contacts[0].employee?.name).isEqualTo("李四")
        assertThat(contacts[1].employee).isNull()
        assertThat(repository.observeIsContact("10003@im.example.com").first()).isFalse()
    }

    @Test
    fun addAndRemove_viaPushes() = runTest {
        data.seed()
        val repository = ContactRepository(data.client, data.holder, backgroundScope)

        repository.observeIsContact("10003@im.example.com").test {
            assertThat(awaitItem()).isFalse()
            repository.add("10003@im.example.com")
            runCurrent()
            assertThat(awaitItem()).isTrue()

            repository.remove("10003@im.example.com")
            runCurrent()
            assertThat(awaitItem()).isFalse()
        }
        assertThat(data.client.calls).containsAtLeast("roster:add:10003@im.example.com", "roster:remove:10003@im.example.com")
    }

    /** 其他端添加的联系人通过推送同步过来 */
    @Test
    fun pushFromOtherDevice() = runTest {
        data.seed()
        val repository = ContactRepository(data.client, data.holder, backgroundScope)

        data.client.pushRoster(RosterItem("10001@im.example.com", RosterSubscription.TO))
        runCurrent()

        assertThat(repository.observeContacts().first { it.isNotEmpty() }.single().employee?.name).isEqualTo("张三")
    }

    /** 登录步骤：获取花名册 */
    @Test
    fun rosterStep_refreshes() = runTest {
        val repository = ContactRepository(data.client, data.holder, backgroundScope)
        RosterStep(repository).run(SessionContext("10001"))
        assertThat(data.client.calls).contains("roster:load")
    }
}
