# 📐 Architecture: ระบบตรวจสอบเบอร์โทรศัพท์แบบ File-Based (No Database)
## สำหรับ JBoss EAP 7.2.0.GA (Java EE 8 / Java 8 or 11)

ระบบนี้ออกแบบมาเพื่อตอบสนองความต้องการ **"ไม่มี Database, อัพโหลดไฟล์แล้วประมวลผล, เก็บไฟล์ไว้ให้ดาวน์โหลดได้ตลอดเวลา และจะถูกลบ/เขียนทับเมื่อมีคนอัพโหลดไฟล์ใหม่เข้ามาเท่านั้น"**

---

## 🏗️ Overview Architecture

```
                               ┌────────────────────────────────────────────────────────┐
                               │                    JBoss Application                   │
                               │                                                        │
 ┌──────────────┐              │ ┌──────────────┐               ┌─────────────────────┐ │
 │   Browser    │───(Upload)──▶│ │  Upload API  │──────────────▶│  File-Based Storage │ │
 │              │              │ └──────────────┘               │ (NFS / Shared Path) │ │
 │              │◀──(Poll Status)│                              │                     │ │
 │  Vue.js 2 /  │              │ ┌──────────────┐               │ - metadata.json     │ │
 │  Vanilla JS  │───(Download)▶│ │ Download API │◀──(Read file)─│ - results.xlsx      │ │
 └──────────────┘              │ └──────────────┘               └─────────────────────┘ │
                               │                                        │ (Read/Write)  │
                               │ ┌──────────────┐                       │               │
                               │ │  Async EJB / │◀──────────────────────┘               │
                               │ │ Thread Pool  │───(Call API Parallel)───┐             │
                               └─└──────────────┘─────────────────────────┼─────────────┘
                                                                          │
                                                ┌─────────────┬───────────┴─────────────┐
                                                ▼             ▼                         ▼
                                           ┌─────────┐   ┌─────────┐               ┌─────────┐
                                           │System A │   │System B │               │System N │
                                           └─────────┘   └─────────┘               └─────────┘
```

---

## ⚙️ การออกแบบ Storage (File-Based State)

เนื่องจากระบบไม่มี Database แต่ต้องการเก็บสถานะและประวัติผลลัพธ์ของ Job ล่าสุดเอาไว้ เราจึงใช้การจัดเก็บข้อมูลลงใน **ไฟล์บน Disk (Filesystem)** แทน โดยแบ่งเป็น 2 ส่วนหลัก:

### 1. ตำแหน่งจัดเก็บไฟล์ (Storage Path)
ควรกำหนดตำแหน่งผ่าน JBoss System Properties หรือ Environment Variable เช่น `-Dnumber.checker.storage.path=/data/number-checker`
* **หากรัน Single Node:** สามารถใช้ Local Disk ของ Server ได้เลย
* **หากรัน Multi-Node (Domain Mode):** **จำเป็น** ต้องใช้ **Shared Directory / Network File System (NFS)** ที่ทุก Nodes สามารถ Read/Write ได้ เพื่อให้ User ดาวน์โหลดไฟล์และดูสถานะได้ถูกต้องไม่ว่าจะเข้าใช้งานผ่าน Node ใดก็ตาม

### 2. โครงสร้างไฟล์ใน Storage Path
ใน Storage Path จะมี Directory ย่อยชื่อ `active_job` ซึ่งเก็บไฟล์ที่สำคัญดังนี้:

```text
/data/number-checker/
└── active_job/
    ├── metadata.json       <-- เก็บสถานะ ปริมาณงาน และ Progress
    ├── input.xlsx          <-- ไฟล์ต้นฉบับที่ User อัพโหลดเข้ามา
    └── results.xlsx        <-- ไฟล์ผลลัพธ์สุดท้ายที่ให้ดาวน์โหลด (มีคอลัมน์ผลตรวจสอบเพิ่มขึ้นมา)
```

เมื่อมี **การอัพโหลดไฟล์ใหม่**:
1. ระบบจะลบไฟล์ทั้งหมดใน Directory `active_job` ทันที (หรือย้ายไปเก็บที่ Backup Path ก่อนเพื่อความปลอดภัย)
2. สร้างไฟล์ `metadata.json` ใหม่เพื่อแสดงสถานะ `RUNNING`
3. เริ่มต้นกระบวนการตรวจสอบข้อมูล

---

## 📊 ตัวอย่างของ `metadata.json` (State Management)

ไฟล์นี้ทำหน้าที่แทนตารางใน Database เพื่อใช้ตรวจสอบว่ามี Job รันอยู่หรือไม่ และทำงานไปถึงไหนแล้ว

```json
{
  "jobId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "status": "RUNNING", 
  "fileName": "phone_list_june_2026.xlsx",
  "uploadedAt": "2026-06-10T12:45:00+07:00",
  "completedAt": null,
  "totalNumbers": 1500,
  "processedNumbers": 450,
  "successCount": 440,
  "failedCount": 10,
  "errorMessage": null
}
```
*สถานะ (status): `PENDING` (รอเริ่ม), `RUNNING` (กำลังทำงาน), `DONE` (เสร็จสมบูรณ์), `FAILED` (ผิดพลาด)*

---

## 🔄 Sequence การทำงาน (เมื่อไม่มี Database)

### 1. ขั้นตอนการอัพโหลดและเริ่มงาน (Upload & Process)
1. User อัพโหลดไฟล์ผ่าน Web UI
2. Server ได้รับไฟล์ และทำการตรวจสอบ (Validation)
3. Server ทำการ **ลบ (Clear) โฟลเดอร์ `active_job` เดิมทิ้งทั้งหมด**
4. บันทึกไฟล์ที่อัพโหลดเข้ามาเป็น `input.xlsx` และสร้าง `metadata.json` เริ่มต้นที่มีสถานะเป็น `RUNNING`
5. ส่งงานให้ **EJB `@Asynchronous`** หรือ **`ManagedExecutorService`** ทำงานใน Background
6. Server ตอบกลับ User ทันทีด้วย HTTP 202 (Accepted)

### 2. ขั้นตอนการประมวลผล (Background Thread)
1. Thread อ่านไฟล์ `input.xlsx` ขึ้นมาประมวลผลแบบ Batch (Chunk-based)
2. ยิง API ไปยังระบบปลายทางแบบ **Parallel** (ใช้ `CompletableFuture` หรือ Thread Pool เพื่อความรวดเร็ว)
3. ทุกๆ Chunk ที่ทำงานเสร็จ (เช่น ทุกๆ 50 เบอร์) จะมีการแก้ไขไฟล์ `metadata.json` บน Disk เพื่ออัพเดตค่า `processedNumbers` (ทำให้หน้าจอ Web UI เห็น Progress เพิ่มขึ้นเรื่อยๆ)
4. เมื่อประมวลผลเสร็จสิ้นทุกระบบ:
   * เขียนผลลัพธ์ทั้งหมดลงในไฟล์ `results.xlsx`
   * อัปเดตสถานะใน `metadata.json` เป็น `DONE` และระบุเวลาเสร็จสิ้นใน `completedAt`

### 3. ขั้นตอนการตรวจสอบสถานะ (Poll Status)
* หน้าจอ Web UI จะยิง API มาที่ `/api/job/status` ทุกๆ 2-3 วินาที
* Server จะอ่านไฟล์ `metadata.json` บน Disk และส่งเนื้อหากลับไปให้หน้าจอแสดงผล

### 4. ขั้นตอนการดาวน์โหลด (Download Result)
* เมื่อปุ่มดาวน์โหลดทำงาน หรือเมื่อคนกลับมาดาวน์โหลดหลังจาก 2-3 วัน
* Server จะเช็คสถานะใน `metadata.json` ว่าเป็น `DONE` หรือไม่
* ส่งไฟล์ `results.xlsx` จากโฟลเดอร์ `active_job` กลับไปให้ User ดาวน์โหลดได้ทันที

---

## 🛠️ Technology Stack & APIs สำหรับ JBoss EAP 7.2

### 1. REST Endpoints (JAX-RS / javax.ws.rs)

| Path | Method | การทำงาน |
|---|---|---|
| `/api/job/upload` | `POST` | รับไฟล์ใหม่, ล้างโฟลเดอร์เก่า, เริ่มต้น Async Thread |
| `/api/job/status` | `GET` | อ่านและตอบกลับเนื้อหาใน `metadata.json` |
| `/api/job/download` | `GET` | ดาวน์โหลดไฟล์ `results.xlsx` จาก Disk |

### 2. Java Code Components (Java EE 8 / JBoss)

* **Upload & IO Handler**: ใช้ `org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput` ของ JBoss เพื่อรับไฟล์อัพโหลด
* **Async Processor**:
  * ใช้ **`javax.enterprise.concurrent.ManagedExecutorService`** เพื่อเรียก Thread Pool ของ JBoss รันแบบ Async
  * ใช้ **`javax.ejb.Asynchronous`** สำหรับการรันงานเบื้องหลัง
* **Excel Writer**: ใช้ **Apache POI 4.x** ในการอ่าน `input.xlsx` และเขียนผลลัพธ์ออกเป็น `results.xlsx`
* **JSON Parser**: ใช้ **JSON-B (javax.json.bind.Jsonb)** หรือ **Jackson** ที่ติดมากับ RESTEasy ในการอ่าน/เขียนไฟล์ `metadata.json`

---

## ⚠️ ข้อควรระวังและการจัดการปัญหา (Handling Edge Cases)

1. **ปัญหาการเขียนไฟล์พร้อมกัน (File Lock / Race Condition)**
   * เนื่องจากหน้าจอ UI จะคอยดึงข้อมูลจาก `metadata.json` ตลอดเวลา ในขณะที่ Background Thread ก็ต้องคอยเขียนไฟล์เดียวกันเพื่ออัปเดตสถานะ
   * **วิธีแก้:** ต้องใช้ระบบ **Synchronized** หรือ **File Lock** ขณะทำการเขียนไฟล์ `metadata.json` เพื่อไม่ให้เกิดอาการไฟล์เสียหาย (File corruption) หรือเกิด Exception ตอนที่มีการอ่านและเขียนพร้อมกัน
2. **หาก Server เกิดดับ (Crash/Restart) ระหว่างทำงาน**
   * หาก Server ดับกลางคัน ไฟล์ `metadata.json` จะค้างสถานะเป็น `RUNNING` ทั้งที่ Thread ตายไปแล้ว
   * **วิธีแก้:** ตอนที่แอปพลิเคชัน Start up (ใช้ `javax.servlet.ServletContextListener` หรือ `@Singleton @Startup` EJB) ให้ทำโปรแกรมเช็คเสมอว่า ถ้าไฟล์ `metadata.json` มีสถานะเป็น `RUNNING` หรือ `PENDING` ให้ปรับสถานะเป็น `FAILED` อัตโนมัติ เพราะแสดงว่าระบบดับระหว่างทำงาน
3. **Domain Mode Clustering (ถ้ามีหลาย Node และไม่ได้ใช้ NFS)**
   * หากติดตั้งระบบโดยไม่มี Shared Disk (NFS) และ User ถูกส่งไปคนละ Node จะไม่เห็นสถานะงานและดาวน์โหลดไม่ได้
   * **วิธีแก้ที่ดีที่สุด:** ต้องผูก Path ของ Directory ไปที่ Mount Point ของ File Server (NFS / Samba Share) ร่วมกัน
