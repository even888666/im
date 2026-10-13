package cn.android666.im.testing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 按 Room 导出的历史 schema（app/schemas/）创建数据库，用于 Robolectric 中的迁移测试。
 *
 * 作用与 room-testing 的 MigrationTestHelper.createDatabase 相同；MigrationTestHelper 只能从 assets 读取 schema，
 * 而 Robolectric 只合并 App 自身的 assets（不包括测试源集的 assets），所以直接从文件读取（踩坑 4.2.12）。
 *
 * 迁移测试的写法：createDatabase(旧版本) → 插入旧数据 → 用 Room（注册全部迁移）打开 → 检查数据。
 * Room 打开时会执行迁移，并用生成的 onValidateSchema 校验迁移后的表结构，迁移写错会直接报错。
 */
object RoomSchemaHelper {

    /** 单元测试的工作目录是 app/，schema 在 app/schemas/{数据库类全名}/{版本}.json */
    private val schemaRoot = File("schemas")

    /**
     * 按指定版本的 schema 创建数据库文件（已存在时先删除）。
     *
     * @param context Context
     * @param databaseClass 数据库类全名
     * @param name 数据库文件名
     * @param version schema 版本
     * @return 已建好表、设置了 user_version 的数据库（调用方插入数据后关闭）
     */
    fun createDatabase(context: Context, databaseClass: String, name: String, version: Int): SQLiteDatabase {
        val file = File(schemaRoot, "$databaseClass/$version.json")
        check(file.exists()) { "找不到 schema 文件 ${file.absolutePath}（修改表结构后需要先编译生成）" }
        val database = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject

        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        for (entity in database.getValue("entities").jsonArray) {
            createEntity(db, entity.jsonObject)
        }
        database["views"]?.jsonArray?.forEach { view ->
            val viewObject = view.jsonObject
            db.execSQL(viewObject.string("createSql").replace("\${VIEW_NAME}", viewObject.string("viewName")))
        }
        // room_master_table 与 identity hash，Room 打开时据此判断 schema 版本
        database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    /**
     * 创建一张表及其索引。
     *
     * @param db 数据库
     * @param entity schema 中的 entity 对象
     */
    private fun createEntity(db: SQLiteDatabase, entity: JsonObject) {
        val table = entity.string("tableName")
        db.execSQL(entity.string("createSql").replace("\${TABLE_NAME}", table))
        entity["indices"]?.jsonArray?.forEach { index ->
            val indexObject = index.jsonObject
            db.execSQL(
                indexObject.string("createSql")
                    .replace("\${TABLE_NAME}", table)
                    .replace("\${INDEX_NAME}", indexObject.string("name")),
            )
        }
    }

    /**
     * 读取字符串字段。
     *
     * @param key 字段名
     * @return 字段值
     */
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
