plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tak.parallaxwallpaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tak.parallaxwallpaper"
        // lockHardwareCanvas() と SeekBar の android:min を使うため 26 以上とする
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 実行時の依存ライブラリはなし（Kotlin 標準ライブラリのみ）。
    // JUnit は単体テスト専用で、APK には含まれない。
    testImplementation("junit:junit:4.13.2")
}
