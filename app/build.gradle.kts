import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.ksp)
}

val abiId: String = if (project.hasProperty("abiId")) project.property("abiId").toString() else "0"
val abiTarget: String = if (project.hasProperty("abiTarget")) project.property("abiTarget").toString() else "armeabi-v7a,arm64-v8a,x86,x86_64"

fun calcVersionCode(): Int = file("versionCode.txt").readText().trim().let { versionCode ->
    versionCode.toInt() + abiId.toInt()
}

android {
    namespace = "io.github.saeeddev94.xray"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.saeeddev94.xray"
        minSdk = 26
        targetSdk = 37
        versionCode = calcVersionCode()
        versionName = "1.0.4"
    }

    splits {
        abi {
            isEnable = true
            reset()
            val targetAbis = abiTarget.split(",").map { it.trim() }.filter { it.isNotBlank() }
            include(*targetAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    val debugKeystoreFile = file("${rootDir}/debug.keystore")
    val debugKeystoreBase64File = file("${rootDir}/debug.keystore.base64")
    if (!debugKeystoreFile.exists() && debugKeystoreBase64File.exists()) {
        val base64Text = debugKeystoreBase64File.readText().replace("\\s".toRegex(), "")
        val decoded = Base64.getDecoder().decode(base64Text)
        debugKeystoreFile.writeBytes(decoded)
    }

    signingConfigs {
        if (debugKeystoreFile.exists()) {
            create("debugConfig") {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            storeFile = file("release.jks")
            storePassword = "android"
            keyAlias = "releasekey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = if (debugKeystoreFile.exists()) {
                signingConfigs.getByName("debugConfig")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    val abiCodes = mapOf(
        "armeabi-v7a" to 1,
        "arm64-v8a" to 2,
        "x86" to 3,
        "x86_64" to 4
    )

    androidComponents {
        onVariants { variant ->
            val baseVersionCode = file("versionCode.txt").readText().trim().toInt()
            variant.outputs.forEach { output ->
                val abiFilter = output.filters.find { 
                    it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI 
                }
                if (abiFilter != null) {
                    val abiCode = abiCodes[abiFilter.identifier] ?: 0
                    output.versionCode.set(baseVersionCode + abiCode)
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.blacksquircle.ui.editorkit)
    implementation(libs.blacksquircle.ui.language.json)
    implementation(libs.google.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.topjohnwu.libsu.core)
    implementation(libs.yuriy.budiyev.code.scanner)
}
