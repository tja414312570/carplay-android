plugins {
    id("com.android.library")
}

android {
    namespace = "com.carplay.airplay"
    compileSdk = 36
    testOptions { unitTests.isReturnDefaultValues = true }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(project(":core-iap2"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    // Crypto: SRP-6a, Ed25519, ChaCha20-Poly1305
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    testImplementation("junit:junit:4.13.2")
}
