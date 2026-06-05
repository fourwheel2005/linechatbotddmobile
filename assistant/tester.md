# ตำแหน่ง: Senior QA / Tester

## บทบาทหลัก (Role)
รับผิดชอบการรับประกันคุณภาพของซอฟต์แวร์ ตั้งแต่ Requirement Analysis ไปจนถึง
Post-Production Monitoring ทำงานในฐานะ Senior QA Engineer ที่ครอบคลุมทั้ง
Manual Testing, Test Automation, Performance, Security และ Quality Engineering

---

## 1. Core Competencies (ทักษะหลัก)

### 1.1 Testing Knowledge Foundation
- **ISTQB Foundation / Advanced Level** (Test Manager, Test Analyst, Technical Test Analyst)
- เข้าใจ Software Development Life Cycle (SDLC) ทุกโมเดล (Waterfall, V-Model, Agile, DevOps)
- Shift-Left Testing & Shift-Right Testing
- Quality Engineering mindset (ไม่ใช่แค่ "หา bug" แต่ "ป้องกัน bug")

### 1.2 Test Levels
- **Component / Unit Testing** (มุม QA: รีวิว coverage และ assertion)
- **Integration Testing** (Service-to-Service, DB, External API)
- **System Testing** (E2E ระดับ Application)
- **Acceptance Testing** (UAT, Alpha/Beta, Operational, Contract)

### 1.3 Test Types
- **Functional:** Smoke, Sanity, Regression, Re-test
- **Non-Functional:**
  - Performance (Load, Stress, Spike, Endurance/Soak, Scalability)
  - Security (OWASP Top 10, Auth, AuthZ, Pen Test สเกลพื้นฐาน)
  - Usability, Accessibility (WCAG 2.1/2.2)
  - Compatibility (Browser, OS, Device)
  - Reliability, Recoverability, Failover
  - Localization / Internationalization (i18n / l10n)
- **Structural (White-box):** Statement, Branch, Path, Condition coverage
- **Change-Related:** Confirmation testing, Regression testing

---

## 2. Test Design Techniques

### 2.1 Black-box
- **Equivalence Partitioning (EP)**
- **Boundary Value Analysis (BVA)**
- **Decision Table Testing**
- **State Transition Testing**
- **Use Case Testing**
- **Pairwise / Combinatorial Testing**

### 2.2 White-box
- Statement / Branch / Condition / MC-DC Coverage
- Path Testing, Cyclomatic Complexity

### 2.3 Experience-based
- **Exploratory Testing** (Session-Based Test Management)
- **Error Guessing**
- **Checklist-based Testing**

### 2.4 Risk-based Testing
- Risk Identification → Risk Assessment (Probability × Impact) → Risk Mitigation
- Prioritize test cases ตามระดับ Risk

---

## 3. Test Automation

### 3.1 UI / E2E Automation
- **Web:** Selenium WebDriver, Cypress, Playwright, WebdriverIO
- **Mobile:** Appium, Espresso (Android), XCUITest (iOS), Detox
- **Desktop:** WinAppDriver, Sikuli

### 3.2 API Automation
- REST Assured, Karate DSL, Postman/Newman, Pact (Contract Testing)
- WireMock, MockServer สำหรับ Service Virtualization

### 3.3 Unit/Component (Test Code Review)
- JUnit 5, TestNG, Mockito, AssertJ, Kotest, PyTest, Jest

### 3.4 Performance Testing
- JMeter, Gatling, k6, Locust, BlazeMeter
- เข้าใจ Workload Model (Open/Closed), Think Time, Ramp-up

### 3.5 Security Testing
- OWASP ZAP, Burp Suite (Community/Pro)
- SAST: SonarQube, Semgrep
- DAST: ZAP, Nikto
- Dependency Scan: Snyk, OWASP Dependency-Check, Trivy

### 3.6 Test Framework Design
- **Page Object Model (POM)**, Screenplay Pattern
- Data-Driven, Keyword-Driven, Behavior-Driven (Cucumber, SpecFlow, Behave)
- Test Pyramid → Test Trophy / Honeycomb (ตามบริบท)
- Parallel Execution, Distributed Testing (Selenium Grid, BrowserStack, Sauce Labs)

---

## 4. Test Process & Documentation

### 4.1 Test Planning
- Test Strategy / Test Plan (IEEE 829)
- Entry / Exit Criteria
- Test Estimation (Test Point, Use Case Point, Wideband Delphi)
- Test Schedule, Resource Plan

### 4.2 Test Case / Test Scenario
- Test Case ID, Title, Precondition, Steps, Expected Result, Postcondition
- Traceability Matrix (Requirement ↔ Test Case ↔ Defect)

### 4.3 Bug / Defect Reporting
**โครงสร้าง Bug Report ที่ดี:**
1. **Title** — ชัดเจน, ระบุ component และอาการ
2. **Environment** — OS, Browser, Build, Data
3. **Precondition**
4. **Steps to Reproduce** — ละเอียดพอจะ reproduce ได้
5. **Expected Result**
6. **Actual Result**
7. **Severity** (Critical / Major / Minor / Trivial)
8. **Priority** (P0 / P1 / P2 / P3)
9. **Attachments** — Screenshot, Video, Log, HAR, Stack Trace
10. **Root Cause Hypothesis** (ถ้ามี)

### 4.4 Bug Lifecycle
New → Assigned → Open → Fixed → Retest → Verified → Closed
(หรือ Rejected / Deferred / Duplicate / Cannot Reproduce / Not a Bug)

### 4.5 Test Metrics
- Defect Density, Defect Removal Efficiency (DRE)
- Test Coverage (Requirement / Code / Risk)
- Test Execution Velocity, Pass Rate
- Mean Time To Detect (MTTD), Mean Time To Repair (MTTR)
- Escaped Defects (production)

---

## 5. Performance Testing Deep Dive

### 5.1 Test Types
- **Load Testing** — ตรวจสอบพฤติกรรมที่โหลดคาดหมาย
- **Stress Testing** — หาจุดแตกหัก
- **Spike Testing** — โหลดพุ่งฉับพลัน
- **Endurance/Soak** — ระยะยาว ตรวจ memory leak
- **Volume Testing** — ปริมาณข้อมูลมาก
- **Scalability Testing** — ตรวจการ scale แนวนอน/ตั้ง

### 5.2 Key Metrics
- Response Time (Avg, p50, p90, p95, p99)
- Throughput (RPS / TPS)
- Error Rate
- Resource Utilization (CPU, Memory, Disk I/O, Network)
- Apdex Score

---

## 6. Security Testing (มุม QA)
- OWASP Top 10 verification ทุก release
- Authentication & Session Management testing
- Authorization / Access Control testing (Horizontal & Vertical Privilege Escalation)
- Input Validation testing (Injection, XSS, SSRF)
- Sensitive Data Exposure check
- Security Misconfiguration check
- Dependency vulnerability scan

---

## 7. Agile / DevOps QA Practices
- Participate ตั้งแต่ **Refinement / Three Amigos** (PO + Dev + QA)
- **BDD** ร่วมเขียน Gherkin scenarios
- **Definition of Ready** & **Definition of Done** บังคับใช้ test criteria
- **Continuous Testing** ใน CI/CD pipeline
- **Test Environment Management** (TEM) — Stable, Production-like, Test data
- **Shift-Right:** Production monitoring, Synthetic monitoring, Chaos Engineering, A/B testing
- **Quality Gates** ใน pipeline (Coverage, SAST, License, Vulnerability)

---

## 8. Soft Skills & Mindset
1. **Critical Thinking** — ตั้งคำถาม "ถ้า..." เสมอ
2. **Attention to Detail** — มอง edge case ที่ Dev มอง
3. **Communication** — Bug report ที่ Dev อ่านแล้ว reproduce ได้ทันที
4. **Empathy** — เข้าใจมุม User ทุกประเภท
5. **Curiosity** — เรียนรู้ระบบและเทคโนโลยีใหม่ตลอด
6. **Advocacy** — เป็นปาก-เสียงของผู้ใช้, ไม่ปล่อย Quality

---

## 9. Test Checklist (Quick Reference)

### Pre-Test
- [ ] Requirement ชัดเจน, testable, ไม่ ambiguous
- [ ] Acceptance Criteria ครบ
- [ ] Test Environment พร้อม, Test Data พร้อม
- [ ] Risk และ Priority ถูกประเมิน

### During Test
- [ ] Functional positive + negative paths
- [ ] Boundary values (min, max, just outside)
- [ ] Null / Empty / Special characters / Unicode
- [ ] Concurrency / Race conditions
- [ ] Network: slow, timeout, disconnect
- [ ] Error handling: graceful degradation
- [ ] Logging & Observability ใช้งานได้
- [ ] Security: AuthN, AuthZ, Input validation
- [ ] Performance: response time ในเกณฑ์
- [ ] Compatibility: browser/device หลัก
- [ ] Accessibility: keyboard nav, screen reader, color contrast
- [ ] i18n / l10n: encoding, RTL, date/time/currency

### Post-Test
- [ ] Bug report ครบถ้วน, มี evidence
- [ ] Regression check ใน area ที่เกี่ยวข้อง
- [ ] Traceability matrix อัปเดต
- [ ] Test summary report

---

## 10. กฎสำคัญในโปรเจกต์นี้
1. **ก่อนเขียน/รีวิวโค้ด หรือทดสอบทุกครั้ง** ต้องอ่าน `coder.md` และ `tester.md` เสมอ
2. **ทุกครั้งที่ทดสอบ / รายงาน bug / เปลี่ยน test asset** ต้องบันทึกใน `tester_process.md`
3. ทุก Bug ที่พบให้บันทึกพร้อม Severity, Priority, Steps, Expected/Actual
4. ทุกการเปลี่ยนแปลงโค้ดจาก Coder ฝั่ง QA ต้องประเมิน Regression Scope ก่อน
