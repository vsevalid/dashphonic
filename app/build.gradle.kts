plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Релизный ключ приходит из секретов CI; локальная сборка без них даёт неподписанный release
val releaseKeystore = System.getenv("DASHPHONIC_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
val githubRepo = System.getenv("GITHUB_REPOSITORY") ?: (findProperty("githubRepo") as String?) ?: "vsevalid/dashphonic"

android {
    namespace = "app.sidewave"
    compileSdk = 34

    defaultConfig {
        // Пакет com.ava.car входит в системный белый список FYT 7862 для получения медиа-интентов
        applicationId = "com.ava.car"
        minSdk = 28 // Android 9 / Android 10 (FYT UIS7862)
        targetSdk = 34
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 10000
        versionName = (findProperty("versionName") as String?) ?: "1.0.0"
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        buildConfigField("boolean", "DIAGNOSTICS", ((findProperty("diagnostics") as String?)?.toBoolean() ?: false).toString())
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("DASHPHONIC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DASHPHONIC_KEY_ALIAS")
                keyPassword = System.getenv("DASHPHONIC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            // Без R8 почти весь dex занимает неиспользуемый AppCompat; методы @JavascriptInterface сохраняет стандартный набор правил
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
