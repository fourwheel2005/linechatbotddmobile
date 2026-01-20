package com.example.linechatbotddmobile.Line;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LineBotConfig {

    @Value("${line.bot.channel-token}")
    private String channelToken;

    @Bean
    public MessagingApiClient messagingApiClient() {
        return MessagingApiClient.builder(channelToken).build();
    }
}