plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.vasmarfas.notivisor"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vasmarfas.notivisor"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        disable += setOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "ObsoleteLintCustomCheck",
            // Fires inside libadb-android, which we cannot edit. Android's own wireless debugging
            // authenticates by pairing code and authorised key rather than by a CA chain, so its
            // TLS trust manager is empty by design; there is no certificate authority in the
            // protocol to check against. The connection never leaves loopback either.
            "TrustAllX509TrustManager"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.zxing.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.dadb)
    implementation(libs.libadb.android)
    implementation(libs.sun.security.android)
    implementation(libs.conscrypt.android)
}
