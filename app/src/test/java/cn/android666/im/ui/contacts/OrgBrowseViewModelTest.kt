package cn.android666.im.ui.contacts

import app.cash.turbine.test
import cn.android666.im.testing.MainDispatcherRule
import cn.android666.im.testing.OrgTestData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [OrgBrowseViewModel] 测试：逐级进入、面包屑跳转、返回 */
@RunWith(RobolectricTestRunner::class)
class OrgBrowseViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val data = OrgTestData()

    @After
    fun tearDown() = data.close()

    /**
     * 列表中各项的显示名，部门加 "D:" 前缀、员工加 "E:" 前缀。
     *
     * @param items 列表
     * @return 名称列表
     */
    private fun names(items: List<OrgItem>) = items.map {
        when (it) {
            is OrgItem.Dept -> "D:${it.department.name}"
            is OrgItem.Member -> "E:${it.employee.name}"
        }
    }

    @Test
    fun navigate_openBackJump() = runTest(mainDispatcherRule.testDispatcher) {
        data.seed()
        val vm = OrgBrowseViewModel(data.repository)

        vm.items.test {
            // 初始值为空列表，随后读出顶级部门
            var items = awaitItem()
            if (items.isEmpty()) items = awaitItem()
            assertThat(names(items)).containsExactly("D:总部")

            vm.open((items.single() as OrgItem.Dept).department)
            assertThat(names(awaitUntilNotEmpty())).containsExactly("D:研发中心", "D:市场部").inOrder()
            assertThat(vm.crumbs.value.map { it.name }).containsExactly(null, "总部").inOrder()

            val rd = vm.items.value.first() as OrgItem.Dept
            vm.open(rd.department)
            // 部门成员按拼音排序：李四、张三
            assertThat(names(awaitUntil { it.any { item -> item is OrgItem.Member } })).containsExactly("E:李四", "E:张三").inOrder()

            vm.jumpTo(0)
            assertThat(names(awaitUntil { names(it) == listOf("D:总部") })).containsExactly("D:总部")
            assertThat(vm.crumbs.value).hasSize(1)
            assertThat(vm.back()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun back_popsOneLevel() = runTest(mainDispatcherRule.testDispatcher) {
        data.seed()
        val vm = OrgBrowseViewModel(data.repository)
        vm.open(cn.android666.im.data.org.Department("d1", "", "总部", 0))
        vm.open(cn.android666.im.data.org.Department("d2", "d1", "研发中心", 2))

        assertThat(vm.back()).isTrue()
        assertThat(vm.crumbs.value.map { it.id }).containsExactly("", "d1").inOrder()
    }
}

/**
 * 等待满足条件的一项（Room 的查询结果可能先发出中间状态）。
 *
 * @param predicate 条件
 * @return 满足条件的项
 */
private suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}

/**
 * 等待非空列表。
 *
 * @return 非空列表
 */
private suspend fun <T> app.cash.turbine.ReceiveTurbine<List<T>>.awaitUntilNotEmpty(): List<T> = awaitUntil { it.isNotEmpty() }
