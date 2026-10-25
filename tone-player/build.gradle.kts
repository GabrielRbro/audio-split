plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.audiosplit.tone"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.audiosplit.tone"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "destination"
    productFlavors {
        create("bluetooth") {
            dimension = "destination"
            applicationId = "io.github.audiosplit.tone.bluetooth"
            resValue("string", "app_name", "Tom A · Bluetooth")
            buildConfigField("String", "DESIRED_OUTPUT", "\"Bluetooth\"")
            buildConfigField("String", "TONE_NAME", "\"Tom A\"")
            buildConfigField("int", "TONE_FREQUENCY_HZ", "440")
        }
        create("phone") {
            dimension = "destination"
            applicationId = "io.github.audiosplit.tone.phone"
            resValue("string", "app_name", "Tom B · Celular")
            buildConfigField("String", "DESIRED_OUTPUT", "\"alto-falante do celular\"")
            buildConfigField("String", "TONE_NAME", "\"Tom B\"")
            buildConfigField("int", "TONE_FREQUENCY_HZ", "880")
        }
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
