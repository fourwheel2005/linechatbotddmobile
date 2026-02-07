package com.example.linechatbotddmobile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // ✅ เติมอันนี้
public class LinechatbotddmobileApplication {

	public static void main(String[] args) {

		System.out.println("Hello");
		SpringApplication.run(LinechatbotddmobileApplication.class, args);
	}

}
