plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.insta.reelsoff"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.insta.reelsoff"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    // No `release` block on purpose, and it is worth saying rather than leaving
    // as an omission. `./gradlew build` therefore produces an unminified, unsigned
    // release APK, which is harmless because the app is only ever installed as
    // debug. Turning on R8 is not a free win here: an accessibility service is
    // reached by name from a system setting, and the rule set is deserialized
    // reflectively, so minification would have to be proven on the device before
    // it could be trusted — and a service Android silently refuses to bind is the
    // worst failure this project has.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }
}

// Room writes the schema it expects to schemas/<db>/<version>.json at build time.
// That is what makes a hand-written Migration verifiable WITHOUT a device: the
// CREATE TABLE in AppDatabase has to match this file exactly, or Room throws when
// it opens an upgraded database. Diff the two on every schema change.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":detection"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // lifecycle-runtime-compose alone, for collectAsStateWithLifecycle. The
    // ViewModel arrives through activity-compose's `by viewModels()`, so
    // lifecycle-viewmodel-compose was never used; lifecycle-runtime-ktx was
    // declared and never called, and comes in under this one anyway. Both were
    // removed and the build verified without them.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // Kept although nothing here declares a @Preview: this is what the Layout
    // Inspector talks to, and it ships in debug builds only. The matching
    // `ui-tooling-preview` was dropped — with no previews it earned nothing, and
    // it was a release dependency.
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
}
