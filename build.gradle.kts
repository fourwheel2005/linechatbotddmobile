plugins {
	java
	id("org.springframework.boot") version "3.4.5"
	id("io.spring.dependency-management") version "1.1.7"

}


group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "Demo project for Spring Boot"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("com.linecorp.bot:line-bot-spring-boot-client:8.4.0") // หรือเวอร์ชันล่าสุด
	implementation("com.linecorp.bot:line-bot-spring-boot-handler:8.4.0")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("com.bucket4j:bucket4j-core:8.10.1")
	implementation("com.linecorp.bot:line-bot-messaging-api-client:8.4.0")
	// 🌟 [เพิ่มใหม่] Spring Security สำหรับทำระบบ Login/Register
	implementation("org.springframework.boot:spring-boot-starter-security")
	// 🌟 [เพิ่มใหม่] เสริมพลังให้ Thymeleaf รู้จักกับ Security (เผื่อซ่อน/โชว์ปุ่มตามสถานะ Login)
	implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

	// 2. Resilience4j (Circuit Breaker & Retry) - ป้องกัน OpenAI ล่มแล้วพาเราล่มด้วย
	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
	compileOnly("org.projectlombok:lombok")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
