plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 支付通知模拟器（只在模拟器 / 电脑端自动化测试里用，不进交付包）。
 *
 * 为什么需要单独一个模块？因为 NotificationListenerService 拿到的是
 * StatusBarNotification.packageName —— 那是「谁发的通知」，由系统按安装包认定，
 * 应用自己改不了。想让 AutoLedger 看到一条 pkg == com.tencent.mm 的通知，
 * 唯一办法就是真的装一个包名叫 com.tencent.mm 的 App 上去。
 * 模拟器里没有真微信，这个包名是空的，正好拿来用。
 *
 * 于是同一个模块配两个 flavor，产出两个「马甲」APK：
 *   :paysim:assembleWechatDebug  -> com.tencent.mm
 *   :paysim:assembleAlipayDebug  -> com.eg.android.AlipayGphone
 */
android {
    namespace = "com.local.paysim"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "spoof"
    productFlavors {
        create("wechat") {
            dimension = "spoof"
            applicationId = "com.tencent.mm"
        }
        create("alipay") {
            dimension = "spoof"
            applicationId = "com.eg.android.AlipayGphone"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 测试工具，跟着 debug 密钥走，避免污染发布签名流程
            signingConfig = signingConfigs.getByName("debug")
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
