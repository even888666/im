package cn.android666.im.ui.contacts

import app.cash.turbine.test
import cn.android666.im.R
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.testing.MainDispatcherRule
import cn.android666.im.testing.OrgTestData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [ContactsViewModel] 测试：搜索（防抖）、下拉刷新失败提示 */
@RunWith(RobolectricTestRunner::class)
class ContactsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val data = OrgTestData()

    @After
    fun tearDown() = data.close()

    @Test
    fun search_debouncedResults_blankShowsEntries() = runTest(mainDispatcherRule.testDispatcher) {
        data.seed()
        val vm = ContactsViewModel(data.repository)

        vm.results.test {
            assertThat(awaitItem()).isNull()
            vm.onQueryChanged("z")
            vm.onQueryChanged("zs")
            advanceTimeBy(ContactsViewModel.SEARCH_DEBOUNCE_MILLIS + 1)
            // 只按最后一次输入搜索
            assertThat(awaitItem()!!.map { it.name }).containsExactly("张三")

            vm.onQueryChanged("研发")
            advanceTimeBy(ContactsViewModel.SEARCH_DEBOUNCE_MILLIS + 1)
            assertThat(awaitItem()!!.map { it.empNo }).containsExactly("10001", "10002")

            vm.onQueryChanged("  ")
            advanceTimeBy(ContactsViewModel.SEARCH_DEBOUNCE_MILLIS + 1)
            assertThat(awaitItem()).isNull()
        }
    }

    @Test
    fun refresh_failure_showsMessage() = runTest(mainDispatcherRule.testDispatcher) {
        data.client.iqResponder = { throw XmppException.Timeout() }
        val vm = ContactsViewModel(data.repository)

        vm.messages.test {
            vm.refresh()
            assertThat(awaitItem()).isEqualTo(R.string.error_timeout)
        }
        assertThat(vm.refreshing.value).isFalse()
    }
}
