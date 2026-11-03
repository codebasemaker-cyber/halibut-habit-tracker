plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.codebasemaker.habittracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codebasemaker.habittracker"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
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
    debugImplementation(libs.compose.ui.tooling)
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
