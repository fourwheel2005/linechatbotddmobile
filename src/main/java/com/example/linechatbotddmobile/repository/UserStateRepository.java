package com.example.linechatbotddmobile.repository;

import com.example.linechatbotddmobile.entity.UserState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserStateRepository extends JpaRepository<UserState, Long> {
    Optional<UserState> findByLineUserId(String lineUserId);

    @Query("""
            select u from UserState u
            where u.currentState in :states
              and u.followUpReminderStartedAt is not null
              and u.followUpReminderStartedAt <= :cutoff
              and coalesce(u.followUpReminderSent, false) = false
            """)
    List<UserState> findDueFollowUpReminders(@Param("states") List<String> states, @Param("cutoff") LocalDateTime cutoff);
}
