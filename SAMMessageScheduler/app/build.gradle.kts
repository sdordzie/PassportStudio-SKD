plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.bigsammy.sammessagescheduler"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bigsammy.sammessagescheduler"
        minSdk = 26
        targetSdk = 35
        versionCode = 60
        versionName = "6.18.1"
    }
    // Permanent release key supplied by CI secrets (SAM_KEYSTORE_PATH / SAM_KEYSTORE_PASSWORD).
    // Every build signed with this key installs over the previous one without losing data.
    val samKeystorePath = System.getenv("SAM_KEYSTORE_PATH")
    val samKeystore = samKeystorePath?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (samKeystore != null) {
            create("samRelease") {
                storeFile = samKeystore
                storePassword = System.getenv("SAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SAM_KEY_ALIAS") ?: "sam"
                keyPassword = System.getenv("SAM_KEY_PASSWORD") ?: System.getenv("SAM_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (samKeystore != null) signingConfigs.getByName("samRelease") else signingConfigs.getByName("debug")
        }
    }
    lint { checkReleaseBuilds = false }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // Keep FragmentActivity request-code handling compatible with Activity 1.10+.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}