package com.example.linechatbotddmobile.repository;

import com.example.linechatbotddmobile.entity.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEvent, String> {

    @Modifying
    @Query("DELETE FROM ProcessedWebhookEvent p WHERE p.processedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
