import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
}

val gitCommitCount =
    providers
        .exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText
        .get()
        .trim()
        .toInt()

// 签名信息读取自根目录 release-signing.properties（不入库），缺失时 release 不签名
val releaseSigning =
    rootProject.file("release-signing.properties").takeIf { it.exists() }?.let { props ->
        Properties().apply { props.inputStream().use { load(it) } }
    }

android {
    namespace = "io.github.yfishyon.doumcp"
    buildToolsVersion = "37.0.0"
    compileSdk = 37

    if (releaseSigning != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = "v1.1.3-fixed.1"
    }

    // 按 ABI 分包：arm64-v8a / armeabi-v7a 各出一个 APK
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = releaseSigning?.let { signingConfigs["release"] }
        }
        debug {
            // 调试包与发布包使用同一签名（覆盖安装不丢登录态，便于真机联调）
            signingConfig = releaseSigning?.let { signingConfigs["release"] }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.mcp.sdk.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.dexkit)
    implementation(libs.fastkv)
}
