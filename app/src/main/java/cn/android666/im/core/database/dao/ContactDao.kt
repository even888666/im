package cn.android666.im.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cn.android666.im.core.database.entity.ContactEntity
import cn.android666.im.core.database.entity.ContactWithEmployee
import kotlinx.coroutines.flow.Flow

/** 花名册（常用联系人）的访问 */
@Dao
interface ContactDao {

    /**
     * 新增或更新条目。
     *
     * @param contact 条目
     */
    @Upsert
    suspend fun upsert(contact: ContactEntity)

    /**
     * 删除条目。
     *
     * @param jid 联系人 bare JID
     */
    @Query("DELETE FROM contact WHERE jid = :jid")
    suspend fun delete(jid: String)

    /** 清空 */
    @Query("DELETE FROM contact")
    suspend fun clear()

    /**
     * 新增或更新多个条目。
     *
     * @param contacts 条目
     */
    @Upsert
    suspend fun upsertAll(contacts: List<ContactEntity>)

    /**
     * 用服务器的完整花名册替换本地数据（登录后获取花名册时）。
     *
     * @param contacts 完整花名册
     */
    @Transaction
    suspend fun replaceAll(contacts: List<ContactEntity>) {
        clear()
        upsertAll(contacts)
    }

    /**
     * 常用联系人（订阅状态为 to / both），附带员工信息，按拼音排序（没有员工信息的排在最后）。
     *
     * @return 持续更新的列表
     */
    @Query(
        """
        SELECT contact.jid AS jid,
               e.jid AS e_jid, e.empNo AS e_empNo, e.name AS e_name, e.pinyin AS e_pinyin, e.initials AS e_initials,
               e.deptId AS e_deptId, e.title AS e_title, e.mobile AS e_mobile, e.phone AS e_phone, e.email AS e_email,
               e.sort AS e_sort, e.avatarHash AS e_avatarHash
        FROM contact LEFT JOIN employee AS e ON e.jid = contact.jid
        WHERE contact.subscription IN ('to', 'both')
        ORDER BY e.pinyin IS NULL, e.pinyin, contact.jid
        """,
    )
    fun observeContacts(): Flow<List<ContactWithEmployee>>

    /**
     * 某人是否为常用联系人。
     *
     * @param jid bare JID
     * @return 持续更新的结果
     */
    @Query("SELECT EXISTS(SELECT 1 FROM contact WHERE jid = :jid AND subscription IN ('to', 'both'))")
    fun observeIsContact(jid: String): Flow<Boolean>
}
