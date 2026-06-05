package com.example.linechatbotddmobile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Schema-only entity for ShedLock JDBC provider.
 * ShedLock writes/reads this table directly via JDBC — JPA never queries it.
 * Purpose: let Hibernate's ddl-auto:update create the table on first startup.
 */
@Entity
@Table(name = "shedlock")
public class ShedlockEntry {

    @Id
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "lock_until", nullable = false)
    private LocalDateTime lockUntil;

    @Column(name = "locked_at", nullable = false)
    private LocalDateTime lockedAt;

    @Column(name = "locked_by", length = 255, nullable = false)
    private String lockedBy;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
