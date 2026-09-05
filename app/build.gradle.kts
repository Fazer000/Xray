import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.ksp)
}

val abiId: String = project.property("abiId").toString()
val abiTarget: String = project.property("abiTarget").toString()

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
        versionName = "12.6.0"
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
