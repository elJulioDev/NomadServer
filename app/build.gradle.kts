plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.eljuliodev.servidormc"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.eljuliodev.servidormc"
        minSdk = 26
        // 28 por diseño: con targetSdk >= 29 Android prohíbe exec() desde el directorio privado
        // (W^X), y el server necesita lanzar el `java` del JRE que vive en filesDir. Es lo que hace
        // MineServe-Mobile. Contrapartida: no publicable en Play (exige 36); ver AGENTS.md.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // targetSdk 28 es intencional (ver arriba); el check de Play no aplica a un build sideload.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}