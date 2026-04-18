package com.example.linechatbotddmobile.service.flow;


import com.example.linechatbotddmobile.entity.UserState;

public interface ServiceFlowHandler {

    // ตรวจสอบว่าคลาสนี้รับผิดชอบบริการชื่อนี้ใช่หรือไม่ (เช่น "ผ่อนบอลลูน")
    boolean supports(String serviceName);

    String getServiceName();

    String processMessage(UserState userState, String userMessage);
}
