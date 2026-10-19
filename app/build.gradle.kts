import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// 从 local.properties 或环境变量读取 release 签名信息（在 android 块外，避免被 Gradle DSL 的 java 属性遮蔽）
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

val clientVersion = rootProject.file("VERSION").readText().trim()
require(Regex("(0|[1-9]\\d?)\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})").matches(clientVersion) && clientVersion != "0.0.0") {
    "VERSION 必须为 major.minor.patch：major 0–99，minor/patch 0–999，且不能为 0.0.0"
}
val versionParts = clientVersion.split('.').map { it.toInt() }
val clientVersionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]

android {
    namespace = "com.trah.accnotify"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trah.accnotify"
        minSdk = 26
        targetSdk = 34
        versionCode = clientVersionCode
        versionName = clientVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // 只保留中英文资源，减少 APK 体积
        resourceConfigurations += listOf("zh", "zh-rCN", "zh-rTW", "en")
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("RELEASE_STORE_FILE") ?: System.getenv("RELEASE_STORE_FILE") ?: "release.keystore")
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
            keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS") ?: ""
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            // 启用代码压缩和混淆
            isMinifyEnabled = true
            // 启用资源压缩
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    
    // 打包优化
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module",
                "kotlin/**",
                "DebugProbesKt.bin"
            )
        }
    }
}

dependencies {
    // AndroidX 核心
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // OkHttp for WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson for JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Preference
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Markwon for Markdown rendering
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")

    // Lottie - 动画库 (保留，体积小且效果好)
    implementation("com.airbnb.android:lottie:6.3.0")

    // ViewPager2 (Android 官方，体积小)
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

