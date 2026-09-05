// CodeSprint Agent 백엔드.
//
// Judge 는 여기 없다. 사용자 코드를 실행하는 것은 별도 Python Worker 이고
// 이 애플리케이션은 큐로만 이야기한다 - 근거: docs/adr/0011-language-boundary.md
plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "dev.codesprint"
version = "0.1.0"

java {
    // PRD 98 은 Java 21 또는 17 을 권한다. 로컬 개발 환경이 17 이라 17 로 맞춘다.
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // golden fixture 를 읽는다. Python oracle 과 같은 파일을 본다.
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    // DB 는 실물로 검증한다. 인메모리 DB 로 바꾸면 Flyway 마이그레이션과
    // PostgreSQL 고유 동작이 검증되지 않는다.
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // 경고를 쌓아두지 않는다.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // golden fixture 위치를 테스트에 알려준다. 저장소 루트 기준이다.
    systemProperty("codesprint.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}
