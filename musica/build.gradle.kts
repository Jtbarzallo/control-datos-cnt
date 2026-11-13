plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val numero = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "ec.jat.musica"
    compileSdk = 35

    defaultConfig {
        applicationId = "ec.jat.musica"
        minSdk = 21
        targetSdk = 34
        versionCode = numero
        versionName = "1.0.$numero"
    }

    signingConfigs {
        create("jat") {
            storeFile = file("../app/jat.keystore")
            storePassword = "jatdatos"
            keyAlias = "jat"
            keyPassword = "jatdatos"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jat")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("jat")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
