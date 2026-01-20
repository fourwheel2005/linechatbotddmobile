package com.example.linechatbotddmobile.Line;

import com.example.linechatbotddmobile.chatgpt.OpenAiService;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.handler.annotation.EventMapping;
import com.linecorp.bot.spring.boot.handler.annotation.LineMessageHandler;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@LineMessageHandler
public class LineBotHandler {

    private final OpenAiService openAiService;
    private final MessagingApiClient messagingApiClient;

    @Autowired
    public LineBotHandler(OpenAiService openAiService, MessagingApiClient messagingApiClient) {
        this.openAiService = openAiService;
        this.messagingApiClient = messagingApiClient;
    }

    @EventMapping
    public void handleTextMessageEvent(MessageEvent event) {

        System.out.println(">>> 1. ได้รับข้อความจาก LINE: " + event);

        if (event.message() instanceof TextMessageContent textMessageContent) {
            String userText = textMessageContent.text();
            String replyToken = event.replyToken();

            try {
                // 2. แจ้งเตือนก่อนส่งไป OpenAI
                System.out.println(">>> 2. กำลังส่งไปถาม OpenAI: " + userText);
                String aiResponse = openAiService.getChatGptResponse(userText);
                System.out.println(">>> 3. OpenAI ตอบกลับมาว่า: " + aiResponse);

                // 3. แจ้งเตือนก่อนตอบกลับ LINE
                System.out.println(">>> 4. กำลังส่งคำตอบกลับไปที่ LINE...");
                messagingApiClient.replyMessage(
                        new ReplyMessageRequest(
                                replyToken,
                                List.of(new TextMessage(aiResponse)),
                                false
                        )
                ).get();
                System.out.println(">>> 5. ตอบกลับสำเร็จ! ✅");

            } catch (Exception e) {
                // 4. ถ้าพังตรงไหน ให้ปริ้นท์สีแดงออกมา
                System.err.println("!!! เกิดข้อผิดพลาด !!!");
                e.printStackTrace();
            }
        }
    }
}