package com.example.linechatbotddmobile.repository;

import com.example.linechatbotddmobile.entity.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatHistoryRepository extends JpaRepository<ChatHistory,Long> {

    List<ChatHistory> findTop10ByLineUserIdOrderByCreatedAtDesc(String lineUserId);


    // ⚠️ ต้องมี @Transactional เอง: derived-delete จะทำ em.remove() ทีละแถว ซึ่งบังคับต้องมี
    // transaction — เมธอดนี้ถูกเรียกจาก ChatFlowManager ที่ตั้งใจไม่ครอบ @Transactional ไว้
    // ถ้าไม่ใส่ จะโยน TransactionRequiredException เมื่อ user มีประวัติแชทให้ลบ
    @Modifying
    @Transactional
    void deleteByLineUserId(String lineUserId);
}
