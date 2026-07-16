package com.example.kairuntime.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "kai_apps.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE apps (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                version TEXT,
                launch_path TEXT NOT NULL,
                manifest_path TEXT NOT NULL,
                icon_path TEXT,
                installation_path TEXT NOT NULL,
                app_type TEXT NOT NULL,
                permissions_json TEXT NOT NULL,
                port INTEGER NOT NULL UNIQUE
            )""".trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(app: InstalledKaiApp) {
        writableDatabase.insertOrThrow("apps", null, app.toValues())
    }

    fun get(id: String): InstalledKaiApp? = readableDatabase.query(
        "apps", null, "id = ?", arrayOf(id), null, null, null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toApp() else null }

    fun getAll(): List<InstalledKaiApp> = readableDatabase.query(
        "apps", null, null, null, null, null, "name COLLATE NOCASE",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toApp()) } }

    fun updatePort(id: String, port: Int) {
        val values = ContentValues().apply { put("port", port) }
        writableDatabase.update("apps", values, "id = ?", arrayOf(id))
    }

    fun delete(id: String) {
        writableDatabase.delete("apps", "id = ?", arrayOf(id))
    }

    private fun InstalledKaiApp.toValues() = ContentValues().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("launch_path", launchPath)
        put("manifest_path", manifestPath)
        put("icon_path", iconPath)
        put("installation_path", installationPath)
        put("app_type", appType)
        put("permissions_json", permissionsJson)
        put("port", port)
    }

    private fun Cursor.toApp() = InstalledKaiApp(
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        version = getString(getColumnIndexOrThrow("version")),
        launchPath = getString(getColumnIndexOrThrow("launch_path")),
        manifestPath = getString(getColumnIndexOrThrow("manifest_path")),
        iconPath = getString(getColumnIndexOrThrow("icon_path")),
        installationPath = getString(getColumnIndexOrThrow("installation_path")),
        appType = getString(getColumnIndexOrThrow("app_type")),
        permissionsJson = getString(getColumnIndexOrThrow("permissions_json")),
        port = getInt(getColumnIndexOrThrow("port")),
    )
}
