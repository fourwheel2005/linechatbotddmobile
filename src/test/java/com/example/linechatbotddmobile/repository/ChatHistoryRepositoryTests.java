package com.example.linechatbotddmobile.repository;

import com.example.linechatbotddmobile.entity.ChatHistory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression test สำหรับบั๊ก TransactionRequiredException ของ derived-delete
 * {@link ChatHistoryRepository#deleteByLineUserId(String)}.
 *
 * ⚠️ สำคัญ: คลาสนี้ตั้งใจ "ไม่" ใส่ @Transactional และไม่ใช้ @DataJpaTest
 * เพราะทั้งสองอย่างจะครอบ transaction ให้เทสอัตโนมัติ → em.remove() เจอ tx เสมอ
 * → ไม่มีวันโยน error → ปิดบังบั๊กตัวจริง (แบบเดียวกับที่ test suite เดิมพลาด)
 *
 * ที่นี่รัน "นอก transaction" เลียนแบบ ChatFlowManager ที่เรียก deleteByLineUserId
 * โดยไม่ครอบ @Transactional ไว้:
 *   - ก่อนแก้ (ไม่มี @Modifying @Transactional บนเมธอด repo) → โยน TransactionRequiredException → เทส FAIL
 *   - หลังแก้ (เมธอดเปิด transaction ของตัวเอง) → ลบสำเร็จ → เทส PASS
 */
@SpringBootTest
class ChatHistoryRepositoryTests {

    private static final String LINE_USER_ID = "U-regression-delete-outside-tx";

    @Autowired
    private ChatHistoryRepository chatHistoryRepository;

    @AfterEach
    void cleanUp() {
        chatHistoryRepository.deleteByLineUserId(LINE_USER_ID);
    }

    @Test
    void deleteByLineUserIdWorksOutsideAmbientTransaction() {
        // arrange: มีประวัติแชทค้างอยู่จริง (save() มี tx ในตัวจาก SimpleJpaRepository)
        chatHistoryRepository.save(newMessage("USER", "สนใจผ่อนบอลลูนครับ"));
        chatHistoryRepository.save(newMessage("ASSISTANT", "ได้เลยครับ รบกวนบอกรุ่นไอโฟน"));
        assertThat(chatHistoryRepository.findTop10ByLineUserIdOrderByCreatedAtDesc(LINE_USER_ID))
                .hasSize(2);

        // act + assert: เรียกนอก transaction — ต้องไม่โยน error (ก่อนแก้จะโยน TransactionRequiredException)
        assertThatCode(() -> chatHistoryRepository.deleteByLineUserId(LINE_USER_ID))
                .doesNotThrowAnyException();

        // assert: แถวหายจริง
        assertThat(chatHistoryRepository.findTop10ByLineUserIdOrderByCreatedAtDesc(LINE_USER_ID))
                .isEmpty();
    }

    private static ChatHistory newMessage(String role, String content) {
        ChatHistory h = new ChatHistory();
        h.setLineUserId(LINE_USER_ID);
        h.setRole(role);
        h.setContent(content);
        return h;
    }
}
