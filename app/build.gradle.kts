plugins {
    id("com.android.application")
}

android {
    namespace = "com.jorge.pixelstatusbar.oneui"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jorge.pixelstatusbar.oneui"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.1-safe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
