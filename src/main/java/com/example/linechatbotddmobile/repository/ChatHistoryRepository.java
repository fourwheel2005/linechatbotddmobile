package com.example.linechatbotddmobile.repository;

import com.example.linechatbotddmobile.entity.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatHistoryRepository extends JpaRepository<ChatHistory,Long> {

    List<ChatHistory> findTop10ByLineUserIdOrderByCreatedAtDesc(String lineUserId);


    void deleteByLineUserId(String lineUserId);
}
