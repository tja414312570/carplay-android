plugins {
    id("com.android.application")
}

val senderVersion = "0.28"

android {
    namespace = "com.carplay.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.carplay.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 28
        versionName = "$senderVersion.0"
    }

    buildFeatures { buildConfig = true }
    flavorDimensions += "distribution"
    productFlavors {
        create("standalone") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_CARLIFE_COMPONENT", "false")
        }
        create("vivoComponent") {
            dimension = "distribution"
            applicationId = "com.baidu.carlife.vivo"
            versionCode = 100034
            versionName = "8.8.4-carplay.$senderVersion"
            buildConfigField("boolean", "IS_CARLIFE_COMPONENT", "true")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
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

}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("carplay-$senderVersion.apk")
        }
    }
}

dependencies {
    implementation("org.jmdns:jmdns:3.6.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(project(":core-iap2"))
    implementation(project(":core-airplay"))
    implementation(project(":adapter-vivo"))
    implementation(project(":adapter-xiaomi"))
    implementation(project(":adapter-generic"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
