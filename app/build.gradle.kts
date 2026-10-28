import java.util.Properties

plugins {
    id("com.android.application")
}

/*
 * Release signing: CI passes the keystore through SIGNING_* environment variables (GitHub secrets,
 * see .github/workflows/release.yml); a local build reads keystore.properties (git-ignored).
 * Without either, the release APK is left unsigned.
 */
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signing(name: String): String? = System.getenv("SIGNING_$name") ?: keystoreProps.getProperty(name.lowercase())

android {
    namespace = "com.brownllama.continuum"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.brownllama.continuum"
        minSdk = 26
        targetSdk = 37
        // CI sets these from the release tag (v1.2.0 → 1.2.0) and the run number.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "1.0"
    }

    signingConfigs {
        create("release") {
            signing("STORE_FILE")?.let { storeFile = rootProject.file(it) }
            storePassword = signing("STORE_PASSWORD")
            keyAlias = signing("KEY_ALIAS")
            keyPassword = signing("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing("STORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
}
