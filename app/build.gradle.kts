import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.codebasemaker.habittracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codebasemaker.habittracker"
        minSdk = 34
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
    }

    signingConfigs {
        // Release key lives outside the repo (keystore.properties is git-ignored).
        if (keystoreProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    // APKs are named Halibut-<version>.apk (debug builds: Halibut-<version>-debug.apk).
    applicationVariants.all {
        val suffix = if (buildType.name == "release") "" else "-${buildType.name}"
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "Halibut-$versionName$suffix.apk"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2g"; it.systemProperty("robolectric.graphicsMode", "NATIVE") }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowsize)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.navigation.compose)
    implementation(libs.documentfile)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.work.testing)
    testImplementation(libs.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Hard constraint: the app must never hold the INTERNET permission (DESIGN.md §1).
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register("check${name}NoInternet") {
            val manifest = layout.buildDirectory.file("intermediates/merged_manifest/${variant.name}/process${name}MainManifest/AndroidManifest.xml")
            inputs.files(manifest).optional()
            doLast {
                val f = manifest.get().asFile
                if (f.exists() && f.readText().contains("android.permission.INTERNET")) {
                    throw GradleException("INTERNET permission found in merged manifest for ${variant.name}")
                }
            }
        }
        afterEvaluate { tasks.findByName("process${name}MainManifest")?.finalizedBy(check) }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
