package cn.android666.im.ui.profile

import app.cash.turbine.test
import cn.android666.im.data.contact.ContactRepository
import cn.android666.im.testing.MainDispatcherRule
import cn.android666.im.testing.OrgTestData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [EmployeeProfileViewModel] 测试 */
@RunWith(RobolectricTestRunner::class)
class EmployeeProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val data = OrgTestData()

    @After
    fun tearDown() = data.close()

    @Test
    fun colleague_withDepartmentPath() = runTest(mainDispatcherRule.testDispatcher) {
        data.seed()
        val vm = EmployeeProfileViewModel(data.repository, ContactRepository(data.client, data.holder, backgroundScope), "10002@im.example.com", "10001@im.example.com")

        val state = vm.state.first { it != null }!!
        assertThat(state.employee?.name).isEqualTo("李四")
        assertThat(state.departmentPath).isEqualTo("总部/研发中心")
        assertThat(state.self).isFalse()
    }

    @Test
    fun self() = runTest(mainDispatcherRule.testDispatcher) {
        data.seed()
        val vm = EmployeeProfileViewModel(data.repository, ContactRepository(data.client, data.holder, backgroundScope), "10001@im.example.com", "10001@im.example.com")
        assertThat(vm.state.first { it != null }!!.self).isTrue()
    }

    /** 不在组织架构中（已离职）：employee 为 null */
    @Test
    fun leftCompany() = runTest(mainDispatcherRule.testDispatcher) {
        data.seed()
        val vm = EmployeeProfileViewModel(data.repository, ContactRepository(data.client, data.holder, backgroundScope), "19999@im.example.com", "10001@im.example.com")
        val state = vm.state.first { it != null }!!
        assertThat(state.employee).isNull()
        assertThat(state.departmentPath).isEmpty()
    }

    /** 添加 / 移除常用联系人：状态随花名册推送更新，并提示结果 */
    @Test
    fun toggleContact() = runTest(mainDispatcherRule.testDispatcher) {
        data.seed()
        val vm = EmployeeProfileViewModel(
            data.repository, ContactRepository(data.client, data.holder, backgroundScope), "10002@im.example.com", "10001@im.example.com",
        )
        vm.messages.test {
            assertThat(vm.state.first { it != null }!!.contact).isFalse()
            vm.toggleContact()
            assertThat(awaitItem()).isEqualTo(cn.android666.im.R.string.profile_contact_added)
            assertThat(vm.state.first { it?.contact == true }!!.contact).isTrue()

            vm.toggleContact()
            assertThat(awaitItem()).isEqualTo(cn.android666.im.R.string.profile_contact_removed)
            assertThat(vm.state.first { it?.contact == false }!!.contact).isFalse()
        }
    }
}
