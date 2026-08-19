# Tester Process Log

บันทึกการทดสอบและรายงาน defect ตาม `tester.md` ข้อ 10

---

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
