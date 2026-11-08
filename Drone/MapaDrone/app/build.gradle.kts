import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mapadrone.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mapadrone.app"
        minSdk = 24
        // O SDK v4 da DJI nao foi atualizado para as regras novas do Android 12+; 30 evita problemas de USB/permissoes.
        targetSdk = 30
        versionCode = 1
        versionName = "0.1"
        multiDexEnabled = true
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        manifestPlaceholders["DJI_APP_KEY"] =
            localProps.getProperty("DJI_APP_KEY", "").ifBlank { "COLOQUE_SUA_APP_KEY" }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "**/libdjivideo.so", "**/libSDKRelativeJNI.so", "**/libFlyForbid.so",
                "**/libduml_vision_bokeh.so", "**/libyuv2.so", "**/libGroudStation.so",
                "**/libFRCorkscrew.so", "**/libUpgradeVerify.so", "**/libFR.so",
                "**/libDJIFlySafeCore.so", "**/libdjifs_jni.so", "**/libsfjni.so",
                "**/libDJICommonJNI.so", "**/libDJICSDKCommon.so",
                "**/libDJIUpgradeCore.so", "**/libDJIUpgradeJNI.so"
            )
        }
        resources { excludes += "META-INF/rxjava.properties" }
    }

    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.multidex:multidex:2.0.1")
    // Layouts internos do SDK da DJI usam estas bibliotecas.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    implementation("com.dji:dji-sdk:4.18") {
        exclude(module = "library-anti-distortion")
        exclude(module = "fly-safe-database")
    }
    compileOnly("com.dji:dji-sdk-provided:4.18")

    testImplementation("junit:junit:4.13.2")
}
