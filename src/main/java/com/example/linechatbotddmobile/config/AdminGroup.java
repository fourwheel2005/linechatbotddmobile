package com.example.linechatbotddmobile.config;

/**
 * 📌 รวม Group ID ของกลุ่มแอดมินไว้ที่เดียว (เดิมฮาร์ดโค้ดกระจายอยู่ 4 จุดในโค้ด)
 *
 * ทางร้านใช้ 2 กลุ่ม แบ่งตาม "ที่มาของเคส" ไม่ใช่ตามชนิดการ์ด:
 *   - MAIN_GROUP_ID  = เคสที่มาจาก flow (รวม emergency ที่เกิดใน flow ด้วย)
 *   - PANIC_GROUP_ID = เคสที่ลูกค้าขอคุยกับคนโดยตรง ตั้งแต่ยังไม่เข้า flow
 *
 * เปลี่ยนกลุ่มแอดมิน แก้ที่นี่ที่เดียวมีผลทุกจุด
 */
public final class AdminGroup {

    /**
     * กลุ่มแอดมินหลัก — รับการ์ดจากฝั่ง flow ทั้งหมด:
     * emergency ที่เกิดใน flow (อายุเกินเกณฑ์ / เคยแกะซ่อม / Face ID เสีย / ติดผ่อน / ตอบมั่วเกิน 2 ครั้ง),
     * เคสจำนำ iCloud, เคส AI ตอบไม่ได้ [CALL_ADMIN], การ์ดตรวจรูป-อนุมัติ-สำเร็จ
     */
    public static final String MAIN_GROUP_ID = "C76744781eae27ba2499edb000665e436";

    /**
     * กลุ่มสำหรับเคส panic — ลูกค้าพิมพ์ "แอดมิน" / "คุยกับคน" / "บอท"
     * หรือกดปุ่มริชเมนู "คุยกับแอดมิน" ซึ่งถูกดักที่ LineWebhookController ก่อนเข้า flow
     */
    public static final String PANIC_GROUP_ID = "Ced29a5fec5e581b47ffa61d9845e71bf";

    private AdminGroup() {
    }
}
