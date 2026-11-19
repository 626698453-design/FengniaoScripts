import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    // 保留上游源码 namespace；独立 applicationId 避免覆盖 Hchat。
    namespace = "h.Hchat"
    compileSdk = providers.gradleProperty("fengniao.compileSdk").orNull?.toInt() ?: 34
    defaultConfig {
        applicationId = "h.fengniao.scripts"
        minSdk = 27
        targetSdk = providers.gradleProperty("fengniao.targetSdk").orNull?.toInt() ?: 34
        versionCode = System.getenv("FENGNIAO_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("FENGNIAO_VERSION_NAME") ?: "0.1.0-10.11.2"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { buildConfig = true }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.version",
            "DebugProbesKt.bin", "META-INF/**/LICENSE*", "META-INF/**/NOTICE*",
            "kotlin/**", "kotlin-tooling-metadata.json", "frameworks/android/*.apk",
            "android/attrs.xml", "android/attrs_manifest.xml", "android/res-map.txt",
            "clst/core.jcst", "export/**", "jadx/core/deobf/conditions/tlds.txt")
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("io.github.billywei01:fastkv:3.0.1")
    implementation(files("libs/dexkit-2.2.0-76551eb-idle.aar"))
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26")
    implementation("dev.rikka.ndk.thirdparty:cxx:1.2.0")
    implementation("com.github.REAndroid:ARSCLib:V1.3.8")
    implementation("io.github.skylot:jadx-dex-input:1.5.5") { exclude(group = "com.google.guava", module = "guava") }
    implementation("com.google.guava:guava:33.5.0-android")
    implementation("com.android.tools.smali:smali-baksmali:3.0.9")
    implementation("com.highcapable.kavaref:kavaref-core:1.1.0")
    runtimeOnly("com.highcapable.kavaref:kavaref-android:1.1.0")
    implementation("com.jakewharton.android.repackaged:dalvik-dx:16.0.1")
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.alibaba.fastjson2:fastjson2:2.0.61.android8")
    implementation(files("libs/terminal-emulator-0.118.0.aar", "libs/terminal-view-0.118.0.aar"))
}
