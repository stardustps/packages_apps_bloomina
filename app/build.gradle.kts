plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.Zerodactyl.bloomina"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.Zerodactyl.bloomina"
        minSdk = 26
        targetSdk = 34
        versionCode = 80604
        versionName = "8.6.4 Beta"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        aidl = true
    }
}

dependencies {
    implementation(libs.bundles.androidx)
    implementation(libs.bundles.libsu)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
}
