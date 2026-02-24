package com.example.linechatbotddmobile.config;


import com.example.linechatbotddmobile.admin.AdminUser;
import com.example.linechatbotddmobile.admin.AdminUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // โชว์หน้าเว็บ Login
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }




}