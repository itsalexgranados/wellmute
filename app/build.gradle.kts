import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

ksp {
    // Keeps every database schema version in git, so future migrations can be tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.wellmute"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wellmute"
        minSdk = 35
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"
    }

    // Release signing reads a git-ignored keystore.properties in the project root.
    // Without that file (CI, F-Droid) release builds stay unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    } else {
        null
    }

    buildTypes {
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    androidResources {
        localeFilters += listOf("en")
    }

    // F-Droid rejects the Google-encrypted dependency metadata blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

/**
 * Fails the build if the merged manifest of any variant declares the INTERNET
 * permission (SPEC §6). Wired into `check`, and runnable alone as `checkNoInternet`.
 */
abstract class CheckNoInternetPermission : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        // Parse the XML instead of searching the text, so comments mentioning the permission don't count.
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifest.get().asFile)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val declared = listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(androidNs, "name") }
        }
        if ("android.permission.INTERNET" in declared) {
            throw GradleException(
                "The merged manifest declares android.permission.INTERNET. " +
                    "This app must never have network access. Find the dependency that adds it " +
                    "and remove it, or strip it with tools:node=\"remove\" if it is unused."
            )
        }
    }
}

val checkNoInternet = tasks.register("checkNoInternet") {
    group = "verification"
    description = "Fails if any variant declares android.permission.INTERNET."
}

androidComponents {
    onVariants { variant ->
        val variantCheck = tasks.register<CheckNoInternetPermission>(
            "check${variant.name.replaceFirstChar(Char::uppercase)}NoInternet"
        ) {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        checkNoInternet.configure { dependsOn(variantCheck) }
    }
}

tasks.named("check") { dependsOn(checkNoInternet) }
