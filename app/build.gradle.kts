import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kazaf.mirrorbackup"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kazaf.mirrorbackup"
        minSdk = 33
        targetSdk = 34
        versionCode = 19
        versionName = "2.8.2"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/mirrorbackup.jks")
            storePassword = "kazaf-mirrorbackup-2026"
            keyAlias = "mirrorbackup"
            keyPassword = "kazaf-mirrorbackup-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Material You 动态取色（对齐 KSU/SukiSU 的 Color Style / Color Spec，同源 materialkolor）
    implementation("com.materialkolor:material-kolor:4.0.5")
    // 液态玻璃管线：内嵌 Kyant0/AndroidLiquidGlass（Apache 2.0，与 SukiSU-Ultra 同源）
}
