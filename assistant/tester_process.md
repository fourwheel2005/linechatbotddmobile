# Tester Process Log

บันทึกการทดสอบและรายงาน defect ตาม `tester.md` ข้อ 10

---

## 2026-08-28 — ลูกค้าบางรายตอบแล้วได้รับ “ระบบประมวลผลขัดข้อง” ซ้ำทุกข้อความ

### Bug report
- **Title:** User state lookup — ลูกค้าบางรายติด generic processing error แบบถาวร และปุ่มคืนร่างบอทอาจใช้ไม่ได้
- **Environment:** LINE production ตามภาพเวลา 10:12–10:16, branch `main` commit `25a36a8`
- **Severity:** Major — ลูกค้าที่โดนจะเดิน flow ต่อไม่ได้ทุกข้อความ และ admin control อาจกู้ state ไม่ได้
- **Priority:** P1 — incidence ต่ำประมาณ 3–4 คนจาก ~1,000 คน/วัน แต่เป็น persistent per-user และกระทบ conversion
- **Precondition:** `user_states` มีมากกว่า 1 row สำหรับ `line_user_id` เดียวกัน ซึ่งเกิดได้เมื่อหลาย webhook ของลูกค้าใหม่ถูก worker คนละ thread ประมวลผลพร้อมกัน
- **Steps to reproduce:** บันทึก `UserState` 2 entity ที่มี `lineUserId` เดียวกัน → เรียก `findByLineUserId(lineUserId)`
- **Expected:** 1 LINE user มี state เดียว และระบบ dispatch ไป flow หรือเงียบใน `ADMIN_MODE` ตาม Business rule
- **Actual:** schema ยอมให้บันทึกซ้ำ; repository ที่ return `Optional<UserState>` throw `IncorrectResultSizeDataAccessException`; text controller ตอบ generic error และ postback controller log error โดยไม่เปลี่ยน state
- **Root cause confidence:** High จาก code path + reproduction test; ต้องรัน duplicate-count query ใน production เพื่อผูกกับ LINE user ทั้ง 3–4 เคสแบบ definitive

### หลักฐานเชิงเทคนิค
1. `UserState` มี index ธรรมดาบน `lineUserId` แต่ไม่มี unique constraint; Hibernate DDL ที่ capture จาก test สร้าง `create index` ไม่ใช่ `create unique index`
2. `findByLineUserId` ประกาศ return เป็น `Optional<UserState>` จึงบังคับผลลัพธ์ไม่เกินหนึ่งแถว
3. Diagnostic integration test ยืนยันว่า insert ID เดียวกัน 2 rows สำเร็จ และ lookup throw `IncorrectResultSizeDataAccessException`
4. Text webhook ครอบ `ChatFlowManager.handleTextMessage()` ด้วย generic catch และข้อความ catch ตรงกับ screenshot ทุกอักขระ
5. `take_case` / `resume_bot` ใช้ `findByLineUserId` เดียวกัน แต่ postback catch เพียง log จึงดูเหมือน “กดแล้วไม่เกิดอะไร” ได้
6. Worker pool dispatch แต่ละ event แยกกัน ไม่มี per-user serialization; idempotency ป้องกันเฉพาะ event ID เดิม ไม่ป้องกันสอง event คนละ ID ของ user เดียวกัน

### Case analysis
| Case | พฤติกรรมที่ควรเห็น | ตรงภาพหรือไม่ |
|---|---|---|
| State เป็น `ADMIN_MODE` และ admin กำลังคุย | บอทเงียบ (`null`) ไม่มี generic error | ไม่ตรง |
| Admin คุยเองแต่ไม่ได้กด `take_case` | บอทยังทำ flow ต่อและอาจตอบแทรก admin | เกี่ยวข้อง แต่ไม่อธิบาย generic error |
| ลูกค้ากลับมาหลังหายไปนาน มี STEP เก่าค้าง | บอทต่อ step เดิมหรือ default reset; ไม่ควร throw | เป็น trigger ที่ทำให้เคสเก่าปรากฏ แต่ไม่ใช่ root cause |
| `lineUserId` ซ้ำใน `user_states` | ทุก text lookup throw; generic error ซ้ำ; postback คืนบอทล้ม | ตรงภาพทั้งหมด |
| AI/OpenAI ล้ม | extractor/screening/chat จับ exception ภายในและคืน fallback เฉพาะ | ไม่ตรงข้อความและไม่น่าจำกัดราย user |
| DB/LINE outage ชั่วคราว | กระทบลูกค้าหลายคนในช่วงเวลาเดียวกัน | ไม่ตรง incidence แบบรายคนถาวร |

### Business behavior clarification
- การตอบลูกค้าด้วยมือใน LINE OA ไม่เปลี่ยน state ของ backend อัตโนมัติ เพราะข้อความขาออกของ admin ไม่เข้ามาเป็น customer webhook
- Admin ต้องกด `take_case` เมื่อต้องการให้บอทเงียบ และกด `resume_bot` เมื่อต้องการให้บอทกลับไป step เดิม — นี่คือ Business behavior ปัจจุบัน
- อย่างไรก็ตาม duplicate-state case ทำให้ทั้งสองปุ่มหา state ไม่ได้ จึงต้องซ่อมข้อมูลซ้ำก่อน; การกดคืนบอทอย่างเดียวไม่แก้ root cause

### Production read-only queries ที่ควรรัน
```sql
SELECT line_user_id, COUNT(*) AS row_count
FROM user_states
GROUP BY line_user_id
HAVING COUNT(*) > 1
ORDER BY row_count DESC;
```

```sql
SELECT id, line_user_id, service_name, current_state, previous_state,
       device_model, capacity, retry_count
FROM user_states
WHERE line_user_id IN (
    SELECT line_user_id
    FROM user_states
    GROUP BY line_user_id
    HAVING COUNT(*) > 1
)
ORDER BY line_user_id, id;
```

### Test execution
- Temporary diagnostic integration test: **Pass** — duplicate insert allowed and single-result lookup throws as predicted
- `./gradlew clean test --no-daemon`: **54 passed, 0 failed, 0 errors** after removing the temporary diagnostic asset
- Local PostgreSQL responded on port 5432 but did not contain database `linechatbotddmobile`; no production/customer data was read or mutated

### Regression scope หากอนุมัติให้แก้
- Existing duplicate cleanup/merge (ต้อง backup และตรวจ 3–4 users ก่อนลบ)
- DB unique constraint on `line_user_id`
- Atomic get-or-create/upsert under concurrent first messages
- Per-user message ordering to prevent lost/out-of-order state transitions
- `take_case`, `resume_bot`, panic mode, old returning customer, rapid multi-message input
- ห้ามเปลี่ยน Business rule: admin takeover = bot silent; resume = return to remembered step

### Fix verification — 2026-08-28
| ID | Scenario | Expected | Result |
|---|---|---|---|
| STATE-001 | Legacy `line_user_id` ซ้ำ 2 rows | เหลือ 1 row, merge model/capacity, current=`ADMIN_MODE`, previous=step ไกลที่สุด | Pass |
| STATE-002 | Startup repair รันซ้ำ | ไม่แก้ข้อมูลปกติและไม่ error | Pass |
| STATE-003 | Insert `line_user_id` ซ้ำหลัง startup | unique index ปฏิเสธ | Pass |
| STATE-004 | สอง instance สร้าง user พร้อมกัน | loser โหลด durable winner หลัง unique conflict | Pass |
| LOCK-001 | ข้อความ user เดียวกัน 2 worker | ไม่เข้า critical section พร้อมกัน | Pass |
| LOCK-002 | ข้อความคนละ user | ทำงานขนานได้ | Pass |
| LOCK-003 | controller lock เรียก flow lock ซ้ำ | reentrant, ไม่ deadlock, cleanup lock entry | Pass |
| ADMIN-001 | Admin กด `take_case` กลาง STEP | current=`ADMIN_MODE`, previous=STEP เดิม | Pass |
| ADMIN-002 | Admin กด `resume_bot` | กลับ previous STEP และไม่เปลี่ยน service | Pass |
| ADMIN-003 | Resume แต่ไม่มี previous/service | fallback `STEP_1_INFO` + `ผ่อนบอลลูน` | Pass |
| REMINDER-001 | Scheduler ถือ candidate เก่าแต่ลูกค้าเดินต่อแล้ว | recheck แล้วไม่ส่ง/ไม่ save state เก่าทับ | Pass |

- Full regression: `./gradlew clean build --no-daemon` — **68 passed, 0 failed, 0 errors**
- PostgreSQL 14 integration และ full Spring context: **Pass**
- Temporary database cleanup: **Verified removed**
- Defect status: **Fixed in code / Awaiting production deployment and post-deploy duplicate-count verification**

## 2026-08-19 — LINE production แสดงราคา iPhone 13 Pro Max ชุดเก่า

### Bug report
- **Title:** Balloon pricing — production ตอบยอดรับซื้อ 13 Pro Max เป็น 9,000 แทน 7,000 บาท
- **Environment:** LINE production (ภาพเวลา 11:19–11:20), branch `main` commit `cce2b41`
- **Severity:** Major — เสนอราคาซื้อผิด กระทบการเงินและความน่าเชื่อถือ
- **Priority:** P0 — rate sheet ใหม่เปลี่ยน 20/22 รุ่น จึงเสี่ยงตอบผิดหลายรุ่น ไม่ได้จำกัดเฉพาะ 13 Pro Max
- **Precondition:** ลูกค้าเข้า flow ผ่อนบอลลูนและผ่านถึง `STEP_5_PRICING` โดย `deviceModel=13 Pro Max`
- **Steps:** เริ่ม flow → ระบุ 13 Pro Max → กรอกข้อมูลจนถึงหน้าเสนอราคา
- **Expected:** ยอดรับซื้อ 7,000; งวด 6/8/10/12/15 = 2,290/1,790/1,590/1,390/1,090 บาท
- **Actual:** LINE แสดงยอดรับซื้อ 9,000 ซึ่งตรงกับ rate sheet/source รุ่นก่อน commit `ff84f2a`
- **Root cause:** deployment gap — image ใหม่ build/push สำเร็จ แต่ไม่มีขั้น deploy/pull/restart production และไม่มี build identity ให้ตรวจ runtime

### Test cases
| ID | Scenario | Expected | Result |
|---|---|---|---|
| PRICE-001 | Response เต็มของแต่ละรุ่น 22 รุ่น | ตรง reference table ทุกอักขระและไม่มีงวดเกิน | Pass |
| PRICE-002 | ทุก model-tenor ที่รองรับ 130 คู่ | admin card ได้เดือนและค่างวดตรงช่อง | Pass |
| PRICE-003 | 13 Pro Max stale fingerprint | มี 7,000 และไม่มี 9,000/2,890/1,190 ชุดเก่า | Pass |
| PRICE-004 | รุ่นนอกตาราง เช่น 12 Pro Max | ไม่เดาราคาและส่งต่อ admin | Pass |
| PRICE-005 | งวดที่รุ่นไม่รองรับ | ไม่ปิดการขายและถามตัวเลือกใหม่ | Pass |
| PRICE-006 | Alias/case/spacing ของชื่อรุ่น | normalize ไปยังราคาเดียวกัน | Pass |
| OPS-001 | `/health` build identity | คืน commit ที่ฝังใน image | Pass |

### Execution summary
- Command: `./gradlew clean test --no-daemon`
- Result: **54 passed, 0 failed, 0 errors**
- Pricing coverage: **22 models, 152 price cells**
- Docker image build: Not run — local Docker daemon unavailable; ไม่กระทบผล Unit Test แต่ต้องให้ CI ยืนยัน image build หลัง push

### Regression scope
- Customer-facing quote ที่ `STEP_5_PRICING`
- Admin success card ที่ `STEP_6_MONTH_SELECTION`
- Model normalization และ unsupported tenor
- Health endpoint contract (เพิ่ม field แบบ backward-compatible)
- CI build/push image และ immutable tag
