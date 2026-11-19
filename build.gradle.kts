plugins {
    id("com.android.application") version "9.4.1" apply false
}

// AGP 9.4.1 内置 Kotlin 2.2.10，读不了 kavaref-core / dexkit 用 Kotlin 2.4.0
// 编的元数据（stdlib 2.4.0）。按 AGP 9.0 发行说明，用 buildscript classpath
// 把 KGP 升到 2.4.0；不能直接 apply org.jetbrains.kotlin.android 插件，
// AGP 9 起会报错。https://developer.android.com/build/releases/agp-9-0-0-release-notes
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}
