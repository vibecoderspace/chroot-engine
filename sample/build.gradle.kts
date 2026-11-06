plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vibecoderspace.chrootengine.sample"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.vibecoderspace.chrootengine.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // chroot-engine executes libchroot-ng.so from nativeLibraryDir.
        jniLibs {
            useLegacyPackaging = true
            // Keep the engine byte-identical while the sample uses the NDK.
            keepDebugSymbols += "**/libchroot-ng.so"
        }
    }
}

dependencies {
    implementation(project(":"))
    implementation(project(":sample:terminal-view"))
    implementation(libs.androidx.annotation)
}
