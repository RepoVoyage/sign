import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.repovoyage.sign"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.repovoyage.sign"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // GO 3S 固件只提供 arm64-v8a 原生库（与 Demo 一致）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 双 flavor：training 为采集/训练通道（独立包名 + .training 后缀），production 为成品
    flavorDimensions += "channel"
    productFlavors {
        create("training") {
            applicationIdSuffix = ".training"
            versionNameSuffix = "-training"
        }
        create("production") {
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        compose = true
    }
    lint {
        // AGP 8.7.3 自带 lint 与 Kotlin 2.3.20 K2 分析 API 不兼容：分析 Activity
        // 类文件即崩（KaCallableMemberCall class/interface 不匹配，lint 自身 bug）。
        // AGP 版本按 §3.2 锁定不升，故关闭 release lintVital 门禁；发布验收（P8）
        // 以人工评审补偿，AGP 升级后应恢复此门禁。
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

// Room schema 导出（迁移测试与版本演进依据，随仓库提交）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.inskmp.camera)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    // 单测用真实 org.json（android.jar stub 不可执行）；测试 classpath 优先，不影响运行时
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
