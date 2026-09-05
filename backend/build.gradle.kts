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
    // API 응답을 계약(contracts/submit-response.schema.json)에 대조한다.
    // 계약과 구현이 갈라지는 것을 사람 눈으로 막을 수는 없다.
    // Reviewer 출력을 계약에 대조한다. 테스트뿐 아니라 런타임에도 필요하다 -
    // 모델이 스키마를 어기고 답할 수 있다.
    implementation("com.networknt:json-schema-validator:1.5.1")
}

// 커리큘럼 데이터를 jar 에 굽는다.
//
// 복사본을 backend/src/main/resources 에 두지 않는다. 그러면 정본이 둘이 되고,
// curriculum/ 을 고친 사람이 backend 쪽을 잊으면 조용히 갈라진다. 빌드 시점에
// 저장소의 한 곳에서 가져온다 - 근거: docs/adr/0012-curriculum-is-packaged-from-one-source.md
val curriculumSource = rootProject.projectDir.parentFile.resolve("curriculum")

// 프롬프트도 같은 원칙이다. 복사본을 두면 저장소의 것과 배포된 것이 갈라지고,
// 그러면 promptVersion 이 가리키는 내용이 무엇인지 알 수 없게 된다.
val promptSource = rootProject.projectDir.parentFile.resolve("reviewer/prompts")

// Reviewer 요청용 계약. 프롬프트에 그대로 넣으므로 런타임에 필요하다 -
// 스키마를 손으로 옮겨 적으면 계약과 프롬프트가 갈라지고, 그때 모델은 프롬프트를 따른다.
val contractSource = rootProject.projectDir.parentFile.resolve("contracts")

tasks.named<ProcessResources>("processResources") {
    from(curriculumSource) {
        into("curriculum")
        include("*.yaml")
    }
    from(promptSource) {
        into("prompts")
        include("*.md")
    }
    from(contractSource) {
        into("contracts")
        include("reviewer-output.llm.schema.json")
    }
    // 커리큘럼이나 프롬프트가 바뀌면 다시 굽는다.
    inputs.dir(curriculumSource)
    inputs.dir(promptSource)
    inputs.dir(contractSource)
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
