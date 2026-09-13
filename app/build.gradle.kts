import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名口令不写进仓库：优先读 local.properties（已被 .gitignore 忽略），其次读同名环境变量。
val localProps = Properties()
rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.inputStream()
    ?.use { localProps.load(it) }

val secret: (String) -> String? = { key ->
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(key)
}

val keystoreFile = rootProject.file(secret("LEDGER_KEYSTORE") ?: "keystore/ledger-release.jks")

android {
    namespace = "com.local.autoledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.local.autoledger"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = secret("LEDGER_STORE_PASSWORD")
            keyAlias = secret("LEDGER_KEY_ALIAS") ?: "ledger"
            keyPassword = secret("LEDGER_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 密钥缺失（例如刚 clone 下来）时退化为未签名包，保证源码可编译
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 电脑端单元测试：解析、分类、判重逻辑全部可以脱离设备验证
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
