package com.example.kairuntime.database

data class InstalledKaiApp(
    val id: String,
    val name: String,
    val version: String?,
    val launchPath: String,
    val manifestPath: String,
    val iconPath: String?,
    val installationPath: String,
    val appType: String,
    val permissionsJson: String,
    val port: Int,
)
