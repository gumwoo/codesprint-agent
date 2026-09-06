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

    // 테스트가 읽는 데이터도 입력이다.
    //
    // problems/ 는 jar 에 굽지 않으므로(ADR-0008) 적어 두지 않으면 gradle 이 입력으로
    // 보지 않는다. 그러면 **case 를 고쳐도 테스트가 다시 돌지 않고 초록이 나온다** -
    // 실제로 그랬다. 데이터만 바꾼 대조군이 통과해 버렸다.
    //
    // curriculum/ 과 프롬프트는 processResources 가 이미 입력으로 잡고 있다.
    inputs.dir(rootProject.projectDir.parentFile.resolve("problems"))
    inputs.dir(rootProject.projectDir.parentFile.resolve("tests"))
}

// -- Reviewer 평가 하네스 (ADR-0016) --------------------------------------
//
// 실제 모델을 부르므로 test 에 섞지 않는다. 느리고 비결정적이라 CI 에 넣으면
// 모델이 그날 다르게 답했다는 이유로 관계없는 PR 이 빨개진다.
val evalReviewer by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "라벨된 오답으로 Reviewer 정확도를 잰다. 로컬 Claude CLI 가 필요하다."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.codesprint.reviewer.ReviewerEvaluation"
    systemProperty("codesprint.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}

// gradle 이 없는 곳에서 위 하네스를 돌리기 위한 것.
//
// 이 저장소에서는 gradle 이 컨테이너 안에서 돌고 claude CLI 는 호스트에 있어서,
// 한 프로세스가 둘을 동시에 볼 수 없다. 의존성 jar 를 build/eval-lib 로 모아 두면
// 호스트에서 gradle 없이 돌릴 수 있다.
//
//   java -cp "backend/build/eval-lib/*;backend/build/classes/java/test;..." //        -Dcodesprint.repoRoot=. dev.codesprint.reviewer.ReviewerEvaluation
val evalLibs by tasks.registering(Sync::class) {
    group = "verification"
    description = "평가 하네스를 gradle 없이 돌리기 위해 의존성 jar 를 모은다."
    from(sourceSets["test"].runtimeClasspath.filter { it.isFile })
    into(layout.buildDirectory.dir("eval-lib"))
}
