plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}
fun parseBool(raw: String?): Boolean {
    return when ((raw ?: "").trim().lowercase()) {
        "1", "true", "yes", "y" -> true
        else -> false
    }
}
val allowFcmDisabled = parseBool(
    (findProperty("allowFcmDisabled") as String?) ?: System.getenv("ALLOW_FCM_DISABLED")
)
val enforceFcmPreflight = parseBool(
    (findProperty("enforceFcmPreflight") as String?) ?: System.getenv("ENFORCE_FCM_PREFLIGHT")
)
// Developer debug flow(IDE run/sync)는 막지 않고, release/deploy 경로만 차단한다.
val enforceFcmForBuildInvocation = gradle.startParameter.taskNames.any { task ->
    val t = task.lowercase()
    val artifactTask = t.contains("assemble") || t.contains("bundle") || t.contains("export")
    artifactTask && t.contains("release")
}
if ((enforceFcmPreflight || enforceFcmForBuildInvocation) && !allowFcmDisabled && !hasGoogleServices) {
    throw GradleException(
        "FCM preflight failed: app/google-services.json missing. " +
            "Set ALLOW_FCM_DISABLED=true only when intentionally shipping without push."
    )
}

val appVersionCodeBase = 3
val appVersionName = "3.175"

// Build label is used both in BuildConfig and in output artifact names.
// Keep it stable per build invocation.
val counterFile = rootProject.file("app/build_counter.txt")
fun readCounter(): Int {
    if (!counterFile.exists()) return 0
    return counterFile.readText().trim().toIntOrNull() ?: 0
}
fun writeCounter(value: Int) {
    counterFile.writeText(value.toString())
}
val isBuildInvocation = gradle.startParameter.taskNames.any { name ->
    name.contains("assemble", ignoreCase = true) ||
        name.contains("bundle", ignoreCase = true) ||
        name.contains("install", ignoreCase = true) ||
        name.contains("export", ignoreCase = true)
}
val buildCounter = if (isBuildInvocation) {
    val next = readCounter() + 1
    writeCounter(next)
    next
} else {
    readCounter()
}
val appBuildLabel = "V3_" + String.format("%02d", buildCounter)
val appVersionCode = (appVersionCodeBase * 100000) + buildCounter

android {
    namespace = "com.example.stock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.stock"
        minSdk = 24
        targetSdk = 36
        // Must be monotonically increasing for side-loaded installs to update cleanly.
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        val serverUrl = "http://16.176.148.77/"
        buildConfigField("String", "DEFAULT_BASE_URL", "\"$serverUrl\"")
        buildConfigField("String", "USB_BASE_URL", "\"http://127.0.0.1:8000/\"")
        buildConfigField("String", "LAN_BASE_URL", "\"http://172.25.15.140:8000/\"")
        buildConfigField("int", "DEFAULT_GATE_LOOKBACK", "20")

        buildConfigField("String", "APP_BUILD_LABEL", "\"$appBuildLabel\"")
        buildConfigField("boolean", "FCM_ENABLED", hasGoogleServices.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("src/fcm/java")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val buildType = variant.buildType
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }

        // AGP 9 no longer exposes output file renaming for APKs via a stable API.
        // Instead, export a versioned APK alongside the normal output.
        val exportTask = tasks.register("export${variantName}Apk") {
            val apkDirProvider = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
            dependsOn("assemble${variantName}")

            doLast {
                val apkDir = apkDirProvider.get().asFile
                val apkFiles = apkDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                    .toList()

                if (apkFiles.isEmpty()) {
                    throw GradleException("No APK produced for variant ${variant.name}. Looked under: ${apkDir.absolutePath}")
                }

                // If there are multiple APKs (splits), export the largest one as the "main" artifact.
                val apk = apkFiles.maxByOrNull { it.length() }!!

                val outDir = layout.buildDirectory.dir("outputs/apk/export/${variant.name}").get().asFile
                outDir.mkdirs()

                // Use a short, versioned filename so repeated downloads don't end up as
                // app-latest (2).apk, app-latest (3).apk, etc.
                val outName = "mstock_v${appVersionName}(${appVersionCode})_${appBuildLabel}_${buildType}.apk"
                val outFile = File(outDir, outName)
                apk.copyTo(outFile, overwrite = true)
                logger.lifecycle("Exported APK: ${outFile.absolutePath}")
            }
        }
        // Convenience: `./gradlew :app:exportDebugApk` just works.
        exportTask.configure { /* already dependsOn assemble above */ }
    }
}

// KSP cache corruption workaround:
// some environments (Android Studio + CLI mixed builds) can leave stale kspCaches and
// trigger "Storage ... file-to-id.tab is already registered".
tasks.named("preBuild").configure {
    doFirst {
        delete(layout.buildDirectory.dir("kspCaches"))
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Keep FlowRow binary signature aligned with debugRuntimeClasspath (1.9.2).
    implementation("androidx.compose.foundation:foundation-layout:1.9.2")
    implementation("androidx.compose.material:material")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.retrofit.core)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.firebase.messaging)

    implementation(libs.kotlinx.datetime)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
