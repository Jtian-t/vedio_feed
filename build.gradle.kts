plugins {
    id("com.android.application") version "8.1.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

afterEvaluate {
    configurations.all {
        // 空的也可以，关键是让依赖解析的时序恢复正常
    }
}