package com.example.linechatbotddmobile;

import com.example.linechatbotddmobile.admin.AdminUser;
import com.example.linechatbotddmobile.admin.AdminUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
public class LinechatbotddmobileApplication {



	public static void main(String[] args) {


		System.out.println("Hello");
		SpringApplication.run(LinechatbotddmobileApplication.class, args);
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}


}
