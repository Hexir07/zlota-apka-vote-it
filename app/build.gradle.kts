import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.voteitapp.voteit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voteitapp.voteit"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Load properties from local.properties
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        }

        buildConfigField("String", "SUPABASE_URL", "\"${localProperties.getProperty("SUPABASE_URL") ?: ""}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProperties.getProperty("SUPABASE_ANON_KEY") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }


    buildTypes {
        release {
            isMinifyEnabled = true   // was false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.16")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity:1.8.2")
    implementation("androidx.core:core:1.12.0")
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}