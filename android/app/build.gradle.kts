plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The iPhone viewer web app lives in /docs (so GitHub Pages can host it too).
// Copy it into the APK so the phone can serve it directly on the home Wi-Fi.
val copyWebApp by tasks.registering(Copy::class) {
    from(rootProject.file("../docs"))
    into(layout.buildDirectory.dir("webassets/web"))
}

android {
    namespace = "com.avriella.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.avriella.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("webassets"))

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.named("preBuild") { dependsOn(copyWebApp) }
