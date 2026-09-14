import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/**
 * Per-build values come from Gradle properties, not text substitution into this
 * file. The runner passes -Pw2a* from the validated config, so no user-controlled
 * string is ever spliced into build logic (TRD §10). providers.gradleProperty is
 * the configuration-cache-safe way to read them.
 *
 * Theme colours are deliberately absent: Compose reads them from config.json at
 * runtime, so no per-build resource file has to be generated.
 */
fun prop(name: String, default: String) = providers.gradleProperty(name).getOrElse(default)

/**
 * Signing credentials come from the environment, never from Gradle properties or a
 * file in the project: gradle.properties gets committed by accident, and anything
 * on a command line is readable from the process list.
 *
 * If these are absent the release variant is left UNSIGNED on purpose. It must
 * never silently fall back to the debug key — a debug-signed artifact that reached
 * Play would be signed by a key nobody controls. scripts/verify-signing.sh turns
 * that unsigned output into a hard failure before anything is published.
 */
fun env(name: String) = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.web2app.shell"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = prop("w2aApplicationId", "dev.web2app.shell")
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = prop("w2aVersionCode", "1").toInt()
        versionName = prop("w2aVersionName", "1.0.0")
        resValue("string", "app_name", prop("w2aAppName", "Web2App"))
        // The splash window is painted by the system before any of our code
        // runs, so this cannot come from config.json at runtime. The runner
        // passes theme.background_color through so the launch screen matches
        // the app instead of flashing white on a dark theme.
        resValue("color", "splash_background", prop("w2aSplashBackground", "#FFFFFF"))
        manifestPlaceholders["w2aCleartext"] = prop("w2aCleartext", "false")
    }

    val keystorePath = env("W2A_KEYSTORE_PATH")
    val keystorePassword = env("W2A_KEYSTORE_PASSWORD")

    signingConfigs {
        if (keystorePath != null && keystorePassword != null) {
            create("upload") {
                storeFile = file(keystorePath)
                storeType = "PKCS12"
                storePassword = keystorePassword
                keyAlias = env("W2A_KEY_ALIAS") ?: "upload"
                // PKCS12 keeps one password for the store and its entries.
                keyPassword = env("W2A_KEY_PASSWORD") ?: keystorePassword

                // minSdk is 24, so every target device supports v2. v1 (JAR
                // signing) exists only for API < 24 and is deprecated from
                // Android 11, so it is off. v3 carries rotation lineage. v4 is for
                // adb incremental install and has no role in Play delivery.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildFeatures {
        compose = true
        // AGP 9 defaults resValues off. defaultConfig.resValue above needs it, and
        // that is how app_name arrives without rewriting any source file.
        resValues = true
    }

    buildTypes {
        named("release") {
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = prop("w2aMinify", "false").toBoolean()
            isShrinkResources = prop("w2aShrinkResources", "false").toBoolean()
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9 uses built-in Kotlin: the org.jetbrains.kotlin.android plugin is gone and
// android.kotlinOptions {} no longer exists. Compiler options live here.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.splashscreen)
    implementation(libs.webkit)

    testImplementation(libs.junit)
}
