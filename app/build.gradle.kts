plugins {
    id("com.android.application")
}

android {
    namespace = "com.jorge.pixelstatusbar.oneui"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jorge.pixelstatusbar.oneui.v4"
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        versionName = "0.8-full-statusbar"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
