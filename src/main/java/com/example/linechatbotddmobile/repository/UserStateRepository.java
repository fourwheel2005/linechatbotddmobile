package com.example.linechatbotddmobile.repository;

import com.example.linechatbotddmobile.entity.UserState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserStateRepository extends JpaRepository<UserState, Long> {
    Optional<UserState> findByLineUserId(String lineUserId);
}