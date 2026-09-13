plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

/**
 * 构建输出目录的安置。
 *
 * 项目路径是 D:\Project\记账助手，含中文。AGP 本身用 android.overridePathCheck 放过了，
 * 但 Gradle 的 test worker 在加载测试类时会在中文路径上翻车 ——
 * 单元测试会全部报 ClassNotFoundException: com.local.autoledger.XxxTest。
 *
 * 把 build 目录挪到纯 ASCII 的旁边目录即可彻底绕开，源码路径不用动。
 * 如果哪天把仓库 clone 到了纯英文路径，这里会自动退化回默认的 <project>/build，
 * 也可以用 -PbuildRoot=某个目录 手动指定。
 */
val asciiBuildRoot: File? = run {
    val override = (findProperty("buildRoot") as String?)?.takeIf { it.isNotBlank() }
    when {
        override != null -> file(override)
        rootDir.absolutePath.all { it.code < 128 } -> null
        else -> File(rootDir.parentFile, "AutoLedger-build")
    }
}

if (asciiBuildRoot != null) {
    allprojects {
        val leaf = project.path.trim(':').replace(':', '-').ifEmpty { "root" }
        layout.buildDirectory.set(File(asciiBuildRoot, leaf))
    }
}
