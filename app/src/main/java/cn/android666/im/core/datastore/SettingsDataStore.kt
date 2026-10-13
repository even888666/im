package cn.android666.im.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 全局配置 DataStore（Android工程架构.md §5.1）：上次登录的工号、加密后的密码、服务器地址、设备级设置。
 * 用户业务数据存放在按工号划分的 Room 数据库中，不放这里。
 *
 * 必须通过这个属性委托获取，保证整个进程只有一个实例（DataStore 不允许同一文件被多个实例同时打开）。
 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
