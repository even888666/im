package cn.android666.im.ui.profile

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
        val vm = EmployeeProfileViewModel(data.repository, "10002@im.example.com", "10001@im.example.com")

        val state = vm.state.first { it != null }!!
        assertThat(state.employee?.name).isEqualTo("李四")
        assertThat(state.departmentPath).isEqualTo("总部/研发中心")
        assertThat(state.self).isFalse()
    }

    @Test
    fun self() = runTest(mainDispatcherRule.testDispatcher) {
        data.seed()
        val vm = EmployeeProfileViewModel(data.repository, "10001@im.example.com", "10001@im.example.com")
        assertThat(vm.state.first { it != null }!!.self).isTrue()
    }

    /** 不在组织架构中（已离职）：employee 为 null */
    @Test
    fun leftCompany() = runTest(mainDispatcherRule.testDispatcher) {
        data.seed()
        val vm = EmployeeProfileViewModel(data.repository, "19999@im.example.com", "10001@im.example.com")
        val state = vm.state.first { it != null }!!
        assertThat(state.employee).isNull()
        assertThat(state.departmentPath).isEmpty()
    }
}
