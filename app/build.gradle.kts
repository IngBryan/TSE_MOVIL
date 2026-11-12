plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// API REST del componente central (WildFly). Ver gradle.properties.
val baseUrl = (findProperty("cargauy.baseUrl") as String?)?.trimEnd('/') ?: "http://10.0.2.2:8080/frontofficeciudadanos-web"

// Login directo contra ID Uruguay (gub.uy). El client_id es publico; el secret queda en el servidor.
fun prop(nombre: String, porDefecto: String) = (findProperty(nombre) as String?) ?: porDefecto
val oidcAuthorize = prop("cargauy.oidcAuthorize", "https://auth-testing.iduruguay.gub.uy/oidc/v1/authorize")
val oidcClientId = prop("cargauy.oidcClientId", "890192")
val oidcScopes = prop("cargauy.oidcScopes", "openid personal_info email document")
val oidcRedirect = prop("cargauy.oidcRedirect", "http://localhost:8080")

android {
    namespace = "uy.carga.movil"
    compileSdk = 35

    defaultConfig {
        applicationId = "uy.carga.movil"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
        buildConfigField("String", "OIDC_AUTHORIZE", "\"$oidcAuthorize\"")
        buildConfigField("String", "OIDC_CLIENT_ID", "\"$oidcClientId\"")
        buildConfigField("String", "OIDC_SCOPES", "\"$oidcScopes\"")
        buildConfigField("String", "OIDC_REDIRECT", "\"$oidcRedirect\"")
    }

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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.exifinterface)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Que el build (tambien el de Docker, que corre con -q) muestre por que fallo un test
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
