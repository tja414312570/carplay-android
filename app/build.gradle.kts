plugins {
    id("com.android.application")
}

val senderVersion = "0.31"

android {
    namespace = "com.carplay.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.carplay.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 31
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
            versionCode = 100036
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

val copyApkToArtifacts = tasks.register("copyApkToArtifacts") {
    val artifactsDir = rootProject.layout.projectDirectory.dir("artifacts")
    val apkFile = layout.buildDirectory.file("outputs/apk/vivoComponent/debug/carplay-$senderVersion.apk")
    inputs.file(apkFile)
    outputs.file(artifactsDir.file("carplay-$senderVersion.apk"))
    doLast {
        val src = apkFile.get().asFile
        if (src.exists()) {
            val targetDir = artifactsDir.asFile
            if (!targetDir.exists()) targetDir.mkdirs()
            src.copyTo(File(targetDir, src.name), overwrite = true)
            logger.lifecycle("Copied ${src.name} to ${targetDir.absolutePath}")
        }
    }
}

tasks.matching { it.name == "assembleVivoComponentDebug" }.configureEach {
    finalizedBy(copyApkToArtifacts)
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
