package com.example.linechatbotddmobile.admin;

import com.example.linechatbotddmobile.bot.BotUser;
import com.example.linechatbotddmobile.bot.BotUserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final BotUserRepository botUserRepository;
    private final ObjectMapper objectMapper;
    private final MessagingApiClient messagingApiClient; // ✅ เพิ่มตัวดึงข้อมูลจาก LINE API
    @Autowired
    private AdminUserRepository adminUserRepository;
    private PasswordEncoder passwordEncoder;

    @Autowired
    public AdminController(BotUserRepository botUserRepository, MessagingApiClient messagingApiClient) {
        this.botUserRepository = botUserRepository;
        this.messagingApiClient = messagingApiClient;
        this.objectMapper = new ObjectMapper();
    }

    // ฟีเจอร์ 1: หน้า Dashboard ดูลูกค้าทั้งหมด
    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        List<BotUser> users = botUserRepository.findAll();

        // 1. หา ID แอดมินทั้งหมดที่มีในระบบ (กรองเอาเฉพาะคนที่ไม่ซ้ำ และไม่เป็น null)
        Set<String> uniqueAdminIds = users.stream()
                .map(BotUser::getHandlerAdminId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 2. ดึงชื่อจาก LINE API มาเก็บใน Map (ID -> ชื่อ)
        Map<String, String> adminNameMap = new HashMap<>();
        for (String adminId : uniqueAdminIds) {
            try {
                var profile = messagingApiClient.getProfile(adminId).get();
                adminNameMap.put(adminId, profile.body().displayName()); // เก็บชื่อลง Map
            } catch (Exception e) {
                // กรณีดึงชื่อไม่ได้ (เช่น บล็อคบอทไปแล้ว) ให้แสดง ID ย่อๆ แทน
                adminNameMap.put(adminId, "Admin (" + adminId.substring(0, 5) + "...)");
            }
        }

        model.addAttribute("users", users);
        model.addAttribute("totalUsers", users.size());
        model.addAttribute("adminNameMap", adminNameMap); // ✅ ส่ง Map รายชื่อไปให้หน้าเว็บ Thymeleaf

        return "dashboard";
    }

    // ฟีเจอร์ 2: หน้าดูประวัติแชทของลูกค้าแต่ละคน (คงเดิม)
    @GetMapping("/chat/{userId}")
    public String chatLog(@PathVariable String userId, Model model) {
        BotUser botUser = botUserRepository.findById(userId).orElse(null);

        if (botUser == null) {
            return "redirect:/admin/dashboard";
        }

        List<Map<String, String>> chatHistory = new ArrayList<>();
        try {
            if (botUser.getChatHistoryJson() != null && !botUser.getChatHistoryJson().isEmpty()) {
                chatHistory = objectMapper.readValue(
                        botUser.getChatHistoryJson(),
                        new TypeReference<List<Map<String, String>>>() {}
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        model.addAttribute("botUser", botUser);
        model.addAttribute("chatHistory", chatHistory);

        return "chat-log";
    }
    @GetMapping("/admin/manage")
    public String manageAdmins(Model model) {
        model.addAttribute("admins", adminUserRepository.findAll());
        return "manage-admins"; // ชื่อไฟล์ HTML
    }

    // ฟังก์ชันรับข้อมูลเพื่อสร้างแอดมินใหม่
    @PostMapping("/admin/add-admin")
    public String addAdmin(@RequestParam String username, @RequestParam String password, Model model) {
        if (adminUserRepository.findByUsername(username).isPresent()) {
            return "redirect:/admin/manage?error=true"; // ถ้าชื่อซ้ำให้เด้งกลับไปพร้อมแจ้ง Error
        }

        AdminUser newUser = new AdminUser();
        newUser.setUsername(username);
        newUser.setPassword(passwordEncoder.encode(password)); // เข้ารหัสผ่านให้ปลอดภัย
        adminUserRepository.save(newUser);

        return "redirect:/admin/manage?success=true";
    }
}