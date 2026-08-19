# Coder Process Log

บันทึกการแก้ไฟล์ + decision ตาม `coder.md` ข้อ 11.2 และ 12.7.7
เรียงจากใหม่ไปเก่า

---

## 2026-08-19 — วิเคราะห์ LINE ยังตอบราคาเก่า + เพิ่ม deployment traceability

### Root cause และหลักฐาน
- Source ปัจจุบันที่ `main`/`origin/main` ระบุ 13 Pro Max = รับซื้อ 7,000 บาท และราคานี้ถูกเพิ่มใน commit `ff84f2a` วันที่ 2026-08-11
- ข้อความในภาพ LINE ระบุ 9,000 บาท ซึ่งตรงแบบ exact fingerprint กับ source ก่อน commit `ff84f2a` (รวมถึงงวด 6 เดือน 2,890 และ 15 เดือน 1,190)
- ราคาไม่ได้เก็บใน DB, `UserState`, AI prompt หรือ Flex template; ทุกครั้งที่เข้า `STEP_5_PRICING` จะเรียก `getPriceForModel()` แล้วสร้าง response ใหม่ทันที
- GitHub Actions ของ commit `ff84f2a` build/push image สำเร็จ แต่ workflow มีเพียง build/push Docker Hub ไม่มีขั้น deploy/pull/restart production ขณะที่ `docker-compose.yaml` ใช้ `build: .` และไม่ได้อ้าง image จาก registry
- สรุป: จุดผิดอยู่หลัง build registry และก่อน runtime production (production ยังรัน container/JAR เก่า) ไม่ใช่ price mapping หรือ response formatter ใน source ปัจจุบัน

### ไฟล์ที่แก้
| ไฟล์ | การเปลี่ยนแปลง |
|---|---|
| `BalloonPriceTableTests.java` | ตรวจ response เต็มแบบ exact ครบ 22 รุ่น, ล็อกไม่ให้ 13 Pro Max ตอบ fingerprint ราคาเก่า, ตรวจ admin card ครบ 130 model-tenor combinations |
| `HealthCheckController.java` | เพิ่ม `buildCommit` ใน `/health` เพื่อพิสูจน์ commit ที่ runtime กำลังรัน |
| `HealthCheckControllerTests.java` | Unit test build identity ใน health response |
| `.github/workflows/main.yml` | บังคับ test ผ่านก่อน build, tag image ทั้ง `latest` และ commit SHA, ใส่ OCI revision label |
| `Dockerfile` | ฝัง `APP_BUILD_COMMIT` และ OCI revision ใน runtime image |
| `BalloonFlowService.java`, `BalloonFlowServiceTests.java` | แก้ comment ตัวอย่างราคาเดิมให้ไม่ขัดกับ rate sheet ปัจจุบัน |

### Design decision
ใช้ build provenance แบบ commit SHA ซึ่งเป็น immutable identifier เพื่อแยกปัญหา source/build/deploy/runtime ได้ตรงจุด โดยยังคงตารางราคาและ flow เดิมทั้งหมด

**Alternative ที่ไม่ทำ:** เพิ่มราคาใน DB หรือ cache invalidation เพราะหลักฐานยืนยันว่าไม่มี persistence/cache ของราคา และจะเพิ่ม complexity โดยไม่แก้ root cause

### Verification
- `./gradlew clean test --no-daemon` — 54 tests, 0 failures, 0 errors
- ตารางราคา: 22 รุ่น, 152 ช่องราคา (22 ราคารับซื้อ + 130 ค่างวด)
- จำลอง response 13 Pro Max ได้ยอดรับซื้อ 7,000 และงวด 6/8/10/12/15 = 2,290/1,790/1,590/1,390/1,090 บาท
- Docker build ในเครื่องนี้ตรวจต่อไม่ได้เพราะ Docker daemon ไม่ได้เปิด; Java build/test และ Dockerfile syntax diff ผ่าน `git diff --check`

### Self-review
- Correctness: ครบ customer response, admin selection, model aliases, unavailable tenor และ stale-rate regression
- Design: ไม่เพิ่ม abstraction ใน pricing path; build identity แยก concern อยู่ที่ health/build pipeline
- Security: เปิดเผยเฉพาะ commit SHA ซึ่งไม่ใช่ secret
- Performance: เพิ่มเพียง string field เดียวใน health response; pricing runtime ไม่เปลี่ยน
- Regression scope: balloon pricing, admin success card, health endpoint, CI image build

## 2026-08-11 — ปรับตารางราคาผ่อนบอลลูนตาม CSV ใหม่ + เลิกรับ iPhone 12

### Requirement
1. ปรับเรทราคาใน `getPriceForModel` ให้ตรงกับ `iphone_balloon_installments.csv` ที่ร้านส่งมา "อย่างแม่นยำทั้งหมด"
2. เปลี่ยนเกณฑ์รับเครื่องเป็น "รับตั้งแต่ iPhone 13 mini ขึ้นไป" (เดิมรับ 12 ขึ้นไป)

### ไฟล์ที่แก้
| ไฟล์ | การเปลี่ยนแปลง |
|---|---|
| `service/flow/BalloonFlowService.java` | ตารางราคา 22 รุ่น (13 mini → 17 Pro Max), รองรับ 17e ในตัวเดารุ่น |
| `util/IphoneModelPolicy.java` | เกณฑ์ 12→13, เพิ่ม 12 เข้ากลุ่มรุ่นที่ไม่รับ, เปลี่ยนชื่อ constant/method ให้สื่อเกณฑ์ใหม่ |
| `service/line/ChatFlowManager.java` | ตามชื่อ method ที่เปลี่ยน |
| `resources/prompt/base-system-prompt.st` | เงื่อนไขรุ่นเครื่อง 12→13 |
| `resources/prompt/extractor-prompt.st` | ช่วงตัวเลขรุ่น 12-17 → 13-17, เพิ่มตัวอย่าง 17e / 17 Air |
| `test/.../BalloonPriceTableTests.java` | ตารางอ้างอิงใหม่ + เคส 21/24 งวด |
| `test/.../IphoneModelPolicyTests.java` | 12 ต้องถูกปฏิเสธ, 13 ขึ้นไปต้องผ่าน |

### สรุปความเปลี่ยนแปลงของข้อมูลราคา
- **20 จาก 22 รุ่นเปลี่ยนราคา** (เหลือ 15 Pro / 15 Pro Max ที่เท่าเดิม) — ราคารับซื้อลดลงเกือบทุกรุ่น เช่น 17 Pro Max 25,000 → 21,000
- **ถอดออก 3 รุ่น:** 12 / 12 Pro / 12 Pro Max
- **เพิ่ม 1 รุ่น:** 17e (รับซื้อ 7,000)
- **งวด 21/24 เหลือแค่ 3 รุ่น:** 16 Pro Max, 17 Pro, 17 Pro Max (เดิม 16 Pro / 17 / 17 Air ก็มี)

### Decision + เหตุผล

**1. Generate โค้ดจาก CSV แทนการพิมพ์มือ**
ตัวเลข 22 รุ่น × สูงสุด 9 ช่อง = ~150 ค่า ถ้าพิมพ์มือมีโอกาสพลาดสูงและตรวจด้วยตายาก
จึงใช้สคริปต์อ่าน CSV แล้ว generate ทั้ง `case` ในโค้ดและตารางอ้างอิงในเทสต์จากแหล่งเดียวกัน
พร้อม validate อัตโนมัติ 2 ข้อ: (ก) งวดต้องต่อเนื่องไม่เว้นช่วง (ข) งวดยาวกว่าต้องจ่ายต่อเดือนถูกกว่า — ผ่านทั้ง 22 แถว

**2. คงโครงสร้าง `BalloonPrice(buyPrice, Map<Integer,Integer>)` เดิม**
CSV ใหม่ยังเป็น "prefix ต่อเนื่อง" ทุกแถว (ไม่มีรุ่นไหนข้ามงวด เช่นมี 18 แต่ไม่มี 15)
โครงสร้างเดิมกับ `price(buy, int...)` จึงรองรับได้โดยไม่ต้องแก้ — ไม่มีเหตุให้เปลี่ยน design
> **Alternative ที่ปฏิเสธ:** ทำ `Map<Integer,Integer>` แบบ sparse ให้กรอกงวดเป็นคู่ key-value
> — ปฏิเสธเพราะอ่านยากกว่า ไม่ตรงกับรูปตารางที่ร้านใช้ และไม่มี requirement รองรับ (YAGNI)

**3. เลิกรับ 12 ที่ `IphoneModelPolicy` ไม่ใช่ที่ตารางราคา**
ถ้าปล่อยให้ 12 ผ่านคัดกรองแล้วไปตกที่ "ไม่มีราคา" ตอน STEP_5 ลูกค้าจะเสียเวลาตอบคำถาม
คัดกรอง 8 ข้อจนจบก่อนถึงจะรู้ว่าร้านไม่รับ — คัดออกตั้งแต่ STEP_2 ตรงกับหลัก Fail Fast (coder.md 9.5)

**4. เปลี่ยนชื่อ `UNSUPPORTED_BELOW_IPHONE_12_MESSAGE` → `..._13_MESSAGE`**
ชื่อต้องบอกเจตนาให้ตรงความจริง (coder.md 9.2 / 12.4.11) การทิ้งเลข 12 ไว้ทั้งที่เกณฑ์เป็น 13
คือ comment ที่โกหกในรูปของชื่อตัวแปร กระทบ 3 ไฟล์ แต่คุ้มกับความชัดเจนระยะยาว

**5. รวมกฎรุ่นที่ไม่รับเป็น constant เดียว (`UNSUPPORTED_MODEL_ALTERNATIVES`)**
เดิม regex 2 ตัวเขียนรายการรุ่นซ้ำกันคำต่อคำ — เพิ่ม 12 เข้าไปต้องแก้ 2 ที่และพลาดง่าย
ตอนนี้แก้ที่เดียว (DRY) ส่วน `(?!\d)` ยังทำหน้าที่กัน 13-17 ไม่ให้ติดร่างแหของกฎ `[1-9]`

**6. ตัวเดารุ่น: `16e` → รองรับ `1[67]e`**
17e เป็นรุ่นใหม่ในตาราง ถ้าไม่แก้ ตอน OpenAI ล่มแล้วลูกค้าพิมพ์ "17e" จะถูกตีเป็น "17"
แล้วเสนอราคา 13,000 แทนที่จะเป็น 7,000 (ต่างเกือบเท่าตัว)

**7. Optimization ที่พิจารณาแล้วไม่ทำ**
`getPriceForModel` สร้าง `LinkedHashMap` ใหม่ทุกครั้งที่เรียก — พิจารณาทำเป็น static map ที่สร้างครั้งเดียว
แต่เมธอดนี้ถูกเรียกแค่ ~2 ครั้งต่อ 1 การสนทนา (ตอนเสนอราคา + ตอนลูกค้าเลือกงวด) ไม่ใช่ hot path
การ cache จะแลกความอ่านง่ายของตารางที่เรียงตรงกับ CSV ไปโดยไม่ได้ประโยชน์วัดได้ (coder.md 9.10 no premature optimization)

### การตรวจสอบ
- `./gradlew build` — 50 เทสต์ ผ่านทั้งหมด
- `BalloonPriceTableTests` ล็อกทุกรุ่น ทุกงวด เทียบ CSV ตัวต่อตัว + ยืนยันว่างวดที่ตารางเว้นว่างต้องไม่ถูกเสนอ
- ยิง response จริงตรวจด้วยตา: 13 mini / 13 Pro Max / 17e / 17 / 17 Pro Max เสนอราคาถูกทุกช่อง
  และ 12 / 12 Pro Max / 11 Pro Max ถูกปฏิเสธด้วยข้อความ "รับ 13-17promax"

### Self-review (checklist ข้อ 10 + 12.5)
- Correctness: edge case งวดที่รุ่นไม่มี, ชื่อรุ่นหลายรูปแบบ, รุ่นนอกตาราง → มีเทสต์ครอบทั้งหมด
- Design: ไม่มี pattern ใหม่ ไม่มี abstraction เพิ่ม
- Security: ไม่มี input ใหม่จากภายนอก / ไม่มี secret
- Performance: ไม่มี query, ไม่มี I/O, ไม่มี loop ที่โตตาม input
- Maintainability: ตัวเลขทั้งหมดมาจาก CSV แหล่งเดียว มี comment บอกที่มาไว้ทั้งในโค้ดและเทสต์
