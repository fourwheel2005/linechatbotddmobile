package com.example.linechatbotddmobile.config;


import com.example.linechatbotddmobile.admin.AdminUser;
import com.example.linechatbotddmobile.admin.AdminUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // ปิด CSRF ชั่วคราวเพื่อให้ LINE Webhook ยิงเข้าได้
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers( "/login", "/css/**", "/js/**").permitAll() // หน้าลงทะเบียนและล็อกอินเข้าได้ทุกคน
                        .requestMatchers("/admin/**").authenticated() // 🔒 URL ที่ขึ้นต้นด้วย /admin ต้อง Login ก่อน!
                        .anyRequest().permitAll() // ปล่อยผ่าน Request อื่นๆ (เช่น LINE Webhook)
                )
                .formLogin(form -> form
                        .loginPage("/login") // ระบุหน้า Login ของเราเอง
                        .defaultSuccessUrl("/admin/dashboard", true) // ล็อกอินเสร็จไปหน้า Dashboard
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // ตัวเข้ารหัสผ่านให้ปลอดภัย
    }

    @Bean
    public UserDetailsService userDetailsService(AdminUserRepository adminUserRepository) {
        return username -> {
            AdminUser admin = adminUserRepository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("ไม่พบผู้ใช้งาน: " + username));

            return User.builder()
                    .username(admin.getUsername())
                    .password(admin.getPassword())
                    .roles("ADMIN")
                    .build();
        };
    }
}
