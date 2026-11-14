import com.android.build.api.variant.SigningConfigInfo
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing (ADR-021). The values come from a properties file outside
// the repository whose path is in HEARCOPY_SIGNING_PROPERTIES. The provider
// is lazy: AGP reads it only when a release packaging task runs, so
// configuration, debug builds, unit tests, lint and the R8 task never touch
// it. A missing variable, a missing file or an empty key leaves it absent,
// and checkReleaseSigning then stops the release packaging. No value is
// ever logged.
val releaseSigning: Provider<SigningConfigInfo> =
    providers.environmentVariable("HEARCOPY_SIGNING_PROPERTIES").map { path ->
        val file = File(path)
        if (!file.isFile) return@map null
        val values = Properties().apply { file.inputStream().use { load(it) } }
        val storeFile = values.getProperty("storeFile").orEmpty()
        val storePassword = values.getProperty("storePassword").orEmpty()
        val keyAlias = values.getProperty("keyAlias").orEmpty()
        val keyPassword = values.getProperty("keyPassword").orEmpty()
        if (storeFile.isEmpty() || storePassword.isEmpty() || keyAlias.isEmpty() || keyPassword.isEmpty()) {
            return@map null
        }
        SigningConfigInfo(File(storeFile), storePassword, keyAlias, keyPassword, "PKCS12")
    }

android {
    namespace = "com.dennycgan.hearcopy"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dennycgan.hearcopy"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 shrinks and optimises; proguard-rules.pro turns obfuscation
            // off (ADR-021). Resources are not shrunk.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.signingConfig.from(releaseSigning)
    }
}

// Fails closed: without a complete signing file the release APK and bundle
// are never packaged, signed or written. The check runs after R8 and the
// release lint, so a build without the file still exercises both.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    val signingReady = releaseSigning.map { true }.orElse(false)
    mustRunAfter("minifyReleaseWithR8", "lintVitalRelease")
    doLast {
        if (!signingReady.get()) {
            throw GradleException(
                "Release signing is not configured. Set HEARCOPY_SIGNING_PROPERTIES " +
                    "to a signing properties file outside the repository. " +
                    "Debug builds need no signing setup.",
            )
        }
    }
}

tasks.named { it in setOf("validateSigningRelease", "packageRelease", "signReleaseBundle") }
    .configureEach { dependsOn(checkReleaseSigning) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.konsist)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.org.json)
}

tasks.withType<Test>().configureEach {
    // The architecture guards read repository files (docs, manifests, build
    // scripts, git file list) that are not declared task inputs, so the test
    // task must never be skipped as up to date.
    outputs.upToDateWhen { false }
}
