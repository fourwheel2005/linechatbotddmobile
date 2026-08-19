package com.example.linechatbotddmobile;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthCheckControllerTests {

    @Test
    void healthResponseIdentifiesTheRunningBuild() {
        HealthCheckController controller = new HealthCheckController("ff84f2a-test");

        Map<String, Object> response = controller.checkHealth().getBody();

        assertThat(response)
                .containsEntry("status", "UP")
                .containsEntry("service", "DD Mobile Line Bot API")
                .containsEntry("buildCommit", "ff84f2a-test")
                .containsKey("timestamp");
    }
}
