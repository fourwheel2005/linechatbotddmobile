package com.example.linechatbotddmobile;

import com.example.linechatbotddmobile.admin.AdminUser;
import com.example.linechatbotddmobile.admin.AdminUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
@EnableScheduling // ✅ เติมอันนี้
public class LinechatbotddmobileApplication {

	@Bean
	public CommandLineRunner initAdmin(AdminUserRepository repo, PasswordEncoder encoder) {
		return args -> {
			// ค้นหาบัญชีชื่อ "admin" ถ้าไม่มีให้สร้างออบเจกต์ใหม่
			AdminUser admin = repo.findByUsername("admin").orElse(new AdminUser());

			admin.setUsername("admin");
			admin.setPassword(encoder.encode("asdfasdf2515")); // รหัสผ่านใหม่

			repo.save(admin);

			System.out.println("✅ อัปเดต/สร้าง แอดมินสำเร็จ! Username: admin / Password: asdfasdf2515");
		};
	}

	public static void main(String[] args) {


		System.out.println("Hello");
		SpringApplication.run(LinechatbotddmobileApplication.class, args);
	}


}
