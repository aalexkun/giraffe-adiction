plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.giraffeaddiction"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.giraffeaddiction"
        // The phone this is written for runs Android 17; 35 keeps the WiFi and FGS code on one path.
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // IPauserService / IForegroundListener, the contract between the app and the Shizuku worker.
        aidl = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Room writes each schema version to app/schemas so migrations can be written against the exact
// CREATE statements it expects (see AppDatabase.MIGRATION_1_2).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Rules, per-day state, settings and the event log.
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Shizuku: binder, permission request, bindUserService. The provider artifact merges the
    // ShizukuProvider manifest pieces and the V3_SUPPORT meta-data.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Typed wrappers for the hidden IActivityManager / IPackageManager calls the worker makes.
    // The stub is compile-only: the real classes live in the framework at runtime.
    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)

    // Lifts hidden-API restrictions in the processes that touch them.
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
