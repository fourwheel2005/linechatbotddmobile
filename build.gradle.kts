plugins {
	java
	id("org.springframework.boot") version "3.4.5"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example" // เปลี่ยนให้ตรงกับ package ของคุณ เช่น com.example.linechatbotddmobile
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
	// 🌟 [สำคัญ] ต้องเพิ่ม Spring Milestones เพื่อให้โหลด Spring AI ได้ (บางเวอร์ชันยังไม่ออกตัวเต็ม)
	maven { url = uri("https://repo.spring.io/milestone") }
}

// 🌟 [เพิ่มใหม่] ประกาศเวอร์ชันของ Spring AI ที่ต้องการใช้งาน
ext {
	set("springAiVersion", "1.0.0-M1") // หรือเวอร์ชันล่าสุดที่คุณใช้งานในโปรเจกต์ต้นแบบ
}

dependencies {
	// --- Spring Boot Basics ---
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-webflux")

	// --- Database & Validation ---
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	runtimeOnly("org.postgresql:postgresql")

	// --- LINE Messaging API ---
	implementation("com.linecorp.bot:line-bot-spring-boot-client:8.4.0")
	implementation("com.linecorp.bot:line-bot-spring-boot-handler:8.4.0")
	implementation("com.linecorp.bot:line-bot-messaging-api-client:8.4.0")

	// --- Thymeleaf & Security ---
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

	// --- Utilities (Lombok, Rate Limiting, Circuit Breaker) ---
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	implementation("com.bucket4j:bucket4j-core:8.10.1")
	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

	// 🌟🌟🌟 [เพิ่มใหม่] Spring AI (สำหรับน้องทันใจ) 🌟🌟🌟
	implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")

	// --- Testing ---
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 🌟 [เพิ่มใหม่] ให้ Dependency Management คุมเวอร์ชันของ Spring AI
dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}