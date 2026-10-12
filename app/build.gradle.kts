plugins {
    id("com.android.application") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "1.9.20"
    id("com.google.dagger.hilt.android") version "2.50"
    id("com.google.devtools.ksp") version "1.9.20-1.0.14"
}

android {
    namespace = "com.example.roleplay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.roleplay"
        minSdk = 26
        targetSdk = 34
        // 版本号：按用户要求这一轮修复/优化**锁在 1.0.2**，打包时用 -Bump none 不再递增。
        // versionCode 按用户要求暂时停留在 3（不再往上走）。注意：若目标机已装了比 3 更高的
        // versionCode，`install -r` 会因「降级」被拒，需先卸载或用 `adb install -r -d`。
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // 真机本地推理引擎开关。默认包恒为 false ——
        // 默认包连本地引擎的代码都不会编译进去（代码在 src/phys/，只有真机包参与编译）。
        buildConfigField("boolean", "LOCAL_ENGINE", "false")
    }

    // ============================================================================
    // 正式版签名（release / physRelease 共用同一把密钥）
    //
    // 密钥文件与口令**不在仓库里**：从本机 %USERPROFILE%\.gradle\gradle.properties 读取
    //   ROLEPLAY_KEYSTORE_FILE / ROLEPLAY_KEYSTORE_PASSWORD
    //   ROLEPLAY_KEY_ALIAS      / ROLEPLAY_KEY_PASSWORD
    // 读不到就退化成"未签名 release"（照样能构建，只是装不上），也不会影响 debug 流程。
    //
    // ⚠️ 这把密钥就是"应用身份"：以后所有正式版都必须用它签名才能覆盖升级。
    //    一旦换密钥，已安装用户必须先卸载 —— 而卸载会清掉全部角色/对话/长期记忆。
    //    所以 roleplay-release.jks 与上面那几条属性请在密码管理器里备份好。
    // ============================================================================
    signingConfigs {
        create("release") {
            val ksFile = (project.findProperty("ROLEPLAY_KEYSTORE_FILE") as String?).orEmpty()
            val ksPass = (project.findProperty("ROLEPLAY_KEYSTORE_PASSWORD") as String?).orEmpty()
            val aliasName = (project.findProperty("ROLEPLAY_KEY_ALIAS") as String?) ?: "roleplay"
            val keyPass = (project.findProperty("ROLEPLAY_KEY_PASSWORD") as String?) ?: ksPass
            if (ksFile.isNotBlank() && ksPass.isNotBlank() && file(ksFile).exists()) {
                storeFile = file(ksFile)
                storePassword = ksPass
                keyAlias = aliasName
                keyPassword = keyPass
            } else {
                logger.lifecycle("[签名] 未找到正式版密钥属性 → release 产物将是未签名 APK（debug/phys 不受影响）")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式版（模拟器包）：isDebuggable = false（release 默认）+ 正式密钥签名
            signingConfig = signingConfigs.getByName("release")
        }

        // 真机（物理机）专用包 —— 与默认包是**两套完全独立的逻辑**：
        //
        // 【默认包】:app:assembleDebug / assembleRelease
        //   - 不内置任何推理引擎，仍然是"连 PC 上的 llama-server"那套
        //   - applicationId = com.example.roleplay，APK 出在 apk/debug|release/
        //   - 本文件里所有 phys 相关配置都不参与，行为与体积零变化
        //
        // 【真机包】:app:assemblePhys
        //   - 内置 models/android-snapdragon 里的 llama.cpp 骁龙引擎
        //     （libllama-server.so 可执行文件 + CPU/Adreno OpenCL/Hexagon HTP 后端 .so）
        //   - 本地模型放手机 /storage/emulated/0/AI角色扮演/模型/，App 自动扫描并本地推理，不依赖 PC
        //   - applicationId = com.example.roleplay.phys → 可与默认包共存，数据各自独立
        //   - 只打 arm64-v8a（引擎只有 arm64），APK 出在 apk/phys/
        //   - 安装后应用名与默认包一致：「AI角色扮演」
        create("phys") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".phys"
            versionNameSuffix = "-phys"
            isDebuggable = true
            matchingFallbacks += listOf("debug")
            // 需求：真机安装后名称和默认包一致
            resValue("string", "app_name", "AI角色扮演")
            buildConfigField("boolean", "LOCAL_ENGINE", "true")
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a")
            }
        }

        // 真机**正式版** —— 与上面的 phys 完全同构，只把 debug 家族换成 release 家族：
        //   · initWith(release) → isDebuggable = false + 正式密钥签名（不再带 application-debuggable）
        //   · applicationId 仍是 com.example.roleplay.phys，所以手机上如果已经装了
        //     调试版真机包，**必须先卸载才能装正式版**，而卸载会清掉角色/对话数据 ——
        //     装之前务必确认（正式版与调试版签名不同，Android 不允许覆盖安装）。
        //   · 任务名 :app:assemblePhysRelease，APK 出在 apk/physRelease/
        create("physRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".phys"
            versionNameSuffix = "-phys"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            matchingFallbacks += listOf("release", "debug")
            // 需求：真机安装后名称和默认包一致
            resValue("string", "app_name", "AI角色扮演")
            buildConfigField("boolean", "LOCAL_ENGINE", "true")
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    // 说明：本轮曾临时加 testBuildType = "phys" 想让仪器化冒烟也能跑真机包，
    // 但 AGP 只给 testBuildType 生成 androidTest 变体、且真机包(.phys)与测试包进程不一致，
    // 4 个用例全挂；已撤销，真机验证走 uiautomator 实测，组件级冒烟由模拟器 UiSmokeTest 覆盖。

    sourceSets {
        // 真机包专属：jniLibs 直接指向引擎目录（不在源码树里复制第二份，避免版本漂移）。
        // 目录里除了各后端 .so，还有一个改名成 libllama-server.so 的可执行文件 ——
        // Android 只允许执行 nativeLibraryDir 里的东西，所以必须走这条路。
        // 默认包不引用这个目录 → 默认 APK 里一个引擎字节都没有。
        getByName("phys") {
            jniLibs.srcDirs(rootProject.file("models/android-snapdragon/jniLibs"))
        }

        // 真机正式版（buildType 名不同 → 默认会去找 src/physRelease/，所以手动指回 src/phys/）：
        // 引擎源码、专属清单、jniLibs 全部与调试版真机包共用同一份，避免两套代码漂移。
        getByName("physRelease") {
            manifest.srcFile("src/phys/AndroidManifest.xml")
            java.srcDirs("src/phys/java")
            jniLibs.srcDirs(rootProject.file("models/android-snapdragon/jniLibs"))
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
        // 真机包/默认包靠 BuildConfig.LOCAL_ENGINE 区分，默认包里它是常量 false，
        // R8/编译器会直接把相关分支消掉。
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 正式版默认会跑 lintVital（lint 的"致命问题"检查），但它需要联网拉
    // com.android.tools.lint:lint-gradle —— 本机是离线构建（--offline + 无外网），
    // 拉不到就直接让整个 release 构建失败。这里关掉这道检查：
    // 代码质量由「模拟器组件冒烟 + 真机 uiautomator 实测 + 手工复核」覆盖。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("androidx.compose.material:material-icons-extended")
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // ui-test-manifest：给 createComposeRule 提供 androidx.activity.ComponentActivity。
    // 真机包（.phys）跑不了它（见上面 testBuildType 的说明），所以只挂 debug。
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}