# ☕ คู่มือการพัฒนา Backend (Java EE 8 on JBoss EAP 7.2)

เอกสารนี้แสดงโครงสร้างโค้ดและการทำงานของระบบหลังบ้าน (Backend) ซึ่งสอดคล้องกับหน้าจอ UI (`index.html`) ที่ได้ออกแบบไว้ โดยประมวลผลแบบ **File-Based** ไม่พึ่งพา Database

---

## 📂 โครงสร้างแพ็กเกจที่แนะนำ (Project Structure)

```text
src/main/
├── java/
│   └── com/
│       └── project/
│           └── checker/
│               ├── config/
│               │   └── JaxRsActivator.java      <-- เปิดใช้งาน JAX-RS REST
│               ├── endpoint/
│               │   └── JobEndpoint.java         <-- REST Endpoint (/api/job)
│               ├── model/
│               │   ├── JobMetadata.java         <-- POJO สำหรับเก็บข้อมูล metadata.json
│               │   └── CheckResultRow.java      <-- POJO สำหรับข้อมูลเบอร์โทรแต่ละบรรทัด
│               └── service/
│                   ├── JobManager.java          <-- จัดการการอ่าน/เขียนไฟล์ลง Disk (Singleton)
│                   └── PhoneCheckProcessor.java <-- ตัวประมวลผลยิง API แบบขนาน (Async EJB)
└── webapp/
    └── index.html                               <-- หน้าจอ UI ที่ได้สร้างไว้
```

---

## 1. ⚙️ JaxRsActivator.java (เปิดใช้งาน REST API)
```java
package com.project.checker.config;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

@ApplicationPath("/api")
public class JaxRsActivator extends Application {
    // ลงทะเบียน REST API อัตโนมัติใน JBoss
}
```

---

## 2. 📦 Model Classes (ใช้ JSON-B ในตัวของ Java EE 8)

### JobMetadata.java
```java
package com.project.checker.model;

import java.time.LocalDateTime;
import java.util.List;

public class JobMetadata {
    private String jobId;
    private String status; // PENDING, RUNNING, DONE, FAILED
    private String fileName;
    private String uploadedAt;
    private String completedAt;
    private int totalNumbers;
    private int processedNumbers;
    private int successCount;
    private int failedCount;
    private String errorMessage;
    private List<CheckResultRow> resultsPreview;

    // Getters and Setters...
}
```

### CheckResultRow.java
```java
package com.project.checker.model;

import java.util.Map;

public class CheckResultRow {
    private String phoneNumber;
    private Map<String, String> systems;      // key: systemCode, value: USED / NOT_USED / ERROR
    private Map<String, String> rawResponses; // key: systemCode, value: response body หรือ error msg

    // Getters and Setters...
}
```

---

## 3. 📂 JobManager.java (Singleton จัดการไฟล์บน Disk)
ทำหน้าที่เขียน-อ่านไฟล์ `metadata.json` โดยมีระบบ `synchronized` ป้องกันการเขียนชนกัน (Race Condition)

```java
package com.project.checker.service;

import com.project.checker.model.JobMetadata;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

@ApplicationScoped
public class JobManager {

    // กำหนด Path เก็บข้อมูล (ดึงจาก JBoss property)
    private static final String STORAGE_PATH = System.getProperty("number.checker.storage.path", "/data/number-checker");
    private File activeJobDir;
    private File metadataFile;
    private File resultsExcelFile;
    private File inputExcelFile;
    
    private final Jsonb jsonb = JsonbBuilder.create();

    @PostConstruct
    public void init() {
        activeJobDir = new File(STORAGE_PATH, "active_job");
        if (!activeJobDir.exists()) {
            activeJobDir.mkdirs();
        }
        metadataFile = new File(activeJobDir, "metadata.json");
        resultsExcelFile = new File(activeJobDir, "results.xlsx");
        inputExcelFile = new File(activeJobDir, "input.xlsx");
    }

    // ล้างงานเก่าทั้งหมดทิ้ง
    public synchronized void clearActiveJob() {
        try {
            if (metadataFile.exists()) metadataFile.delete();
            if (resultsExcelFile.exists()) resultsExcelFile.delete();
            if (inputExcelFile.exists()) inputExcelFile.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // บันทึกไฟล์อัปโหลดเริ่มต้น
    public File getInputExcelFile() {
        return inputExcelFile;
    }

    public File getResultsExcelFile() {
        return resultsExcelFile;
    }

    // อ่านสถานะล่าสุดจาก Disk
    public synchronized JobMetadata getMetadata() {
        if (!metadataFile.exists()) {
            return null; // ไม่มี Job ค้างอยู่ในระบบ
        }
        try {
            String jsonContent = new String(Files.readAllBytes(metadataFile.toPath()));
            return jsonb.fromJson(jsonContent, JobMetadata.class);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // บันทึกสถานะล่าสุดลง Disk
    public synchronized void saveMetadata(JobMetadata metadata) {
        try {
            String jsonContent = jsonb.toJson(metadata);
            Files.write(metadataFile.toPath(), jsonContent.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

---

## 4. 🔌 JobEndpoint.java (API สำหรับรับไฟล์ และส่งข้อมูลหน้าจอ)
ใช้ความสามารถของ **JBoss RESTEasy Multipart** เพื่อรองรับการ Upload ไฟล์

```java
package com.project.checker.endpoint;

import com.project.checker.model.JobMetadata;
import com.project.checker.service.JobManager;
import com.project.checker.service.PhoneCheckProcessor;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/job")
public class JobEndpoint {

    @Inject
    private JobManager jobManager;

    @Inject
    private PhoneCheckProcessor checkProcessor;

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_PLAIN)
    public Response uploadFile(MultipartFormDataInput input) {
        try {
            Map<String, List<InputPart>> uploadForm = input.getFormDataMap();
            
            // 1. ดึงไฟล์ออกมา
            List<InputPart> fileParts = uploadForm.get("file");
            if (fileParts == null || fileParts.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity("ไม่พบไฟล์แนบ").build();
            }
            InputPart filePart = fileParts.get(0);
            InputStream inputStream = filePart.getBody(InputStream.class, null);
            
            // ดึงชื่อไฟล์ดั้งเดิม
            String fileName = "uploaded_file.xlsx"; // หาดึงจาก Header Content-Disposition ได้
            
            // 2. ดึงรายชื่อระบบที่ต้องการตรวจ
            List<InputPart> systemParts = uploadForm.get("systems");
            String targetSystemsStr = systemParts != null ? systemParts.get(0).getBodyAsString() : "";
            String[] targetSystems = targetSystemsStr.split(",");

            // 3. เคลียร์ข้อมูลเก่าบน Disk และบันทึกไฟล์ใหม่ลงไป
            jobManager.clearActiveJob();
            Files.copy(inputStream, jobManager.getInputExcelFile().toPath(), StandardCopyOption.REPLACE_EXISTING);

            // 4. บันทึกข้อมูลเริ่มต้นลง metadata.json
            JobMetadata metadata = new JobMetadata();
            metadata.setJobId(UUID.randomUUID().toString());
            metadata.setStatus("RUNNING");
            metadata.setFileName(fileName);
            metadata.setUploadedAt(LocalDateTime.now().toString());
            metadata.setProcessedNumbers(0);
            metadata.setSuccessCount(0);
            metadata.setFailedCount(0);
            jobManager.saveMetadata(metadata);

            // 5. รันประมวลผลเบื้องหลัง (Async)
            checkProcessor.startProcessing(targetSystems);

            return Response.status(Response.Status.ACCEPTED).entity("อัปโหลดสำเร็จ กำลังเริ่มระบบงาน").build();
            
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus() {
        JobMetadata metadata = jobManager.getMetadata();
        if (metadata == null) {
            return Response.status(Response.Status.NO_CONTENT).build(); // 204
        }
        return Response.ok(metadata).build();
    }

    @GET
    @Path("/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadFile() {
        JobMetadata metadata = jobManager.getMetadata();
        if (metadata == null || !"DONE".equals(metadata.getStatus())) {
            return Response.status(Response.Status.NOT_FOUND).entity("ไม่มีไฟล์ผลลัพธ์ที่ตรวจเสร็จแล้ว").build();
        }
        
        java.io.File file = jobManager.getResultsExcelFile();
        if (!file.exists()) {
             return Response.status(Response.Status.NOT_FOUND).build();
        }

        Response.ResponseBuilder response = Response.ok((Object) file);
        response.header("Content-Disposition", "attachment; filename=\"results_" + metadata.getFileName() + "\"");
        return response.build();
    }
}
```

---

## 5. ⚡ PhoneCheckProcessor.java (ยิง API ตรวจเช็คแบบ Async ด้วย Java EE Concurrency)
ใช้ **ManagedExecutorService** ในการดึง Thread Pool ของ JBoss EAP มาทำงานในเบื้องหลัง เพื่อป้องกันไม่ให้ Thread ของ API ค้างตอนรันไฟล์ใหญ่

```java
package com.project.checker.service;

import com.project.checker.model.JobMetadata;
import com.project.checker.model.CheckResultRow;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.annotation.Resource;
import javax.ejb.Asynchronous;
import javax.ejb.Stateless;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.inject.Inject;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Stateless
public class PhoneCheckProcessor {

    @Inject
    private JobManager jobManager;

    // ดึง Thread Pool ของ JBoss EAP มาทำงาน
    @Resource(name = "DefaultManagedExecutorService")
    private ManagedExecutorService executor;

    @Asynchronous
    public void startProcessing(String[] targetSystems) {
        JobMetadata metadata = jobManager.getMetadata();
        if (metadata == null) return;

        try {
            // 1. อ่านเบอร์โทรศัพท์จาก Excel (ใช้ Apache POI)
            List<String> phoneNumbers = readPhoneNumbersFromExcel(jobManager.getInputExcelFile());
            metadata.setTotalNumbers(phoneNumbers.size());
            jobManager.saveMetadata(metadata);

            List<CheckResultRow> results = new ArrayList<>();
            int processedCount = 0;
            int successCount = 0;
            int failedCount = 0;

            // 2. ลูปยิงเช็ค API ของแต่ละระบบ
            // ในการทำงานจริง แนะนำให้ใช้ CompletableFuture ยิงไปหลายๆ ระบบขนานกัน
            for (String phone : phoneNumbers) {
                CheckResultRow row = new CheckResultRow();
                row.setPhoneNumber(phone);
                
                Map<String, String> systemStatus = new HashMap<>();
                Map<String, String> rawResponses = new HashMap<>();
                
                boolean rowSuccess = true;
                
                for (String systemCode : targetSystems) {
                    // เรียกฟังก์ชันเชื่อมต่อไปยัง Intranet API ของแต่ละระบบ
                    try {
                        String apiResult = callIntranetApi(systemCode, phone); // ยิงจริง HTTP Client
                        systemStatus.put(systemCode, apiResult); // "USED" หรือ "NOT_USED"
                        rawResponses.put(systemCode, "Success");
                    } catch (Exception ex) {
                        rowSuccess = false;
                        systemStatus.put(systemCode, "ERROR");
                        rawResponses.put(systemCode, ex.getMessage());
                    }
                }
                
                row.setSystems(systemStatus);
                row.setRawResponses(rawResponses);
                results.add(row);
                
                processedCount++;
                if (rowSuccess) {
                    successCount++;
                } else {
                    failedCount++;
                }

                // 3. บันทึกผลและ Progress ลง Disk ทุกๆ 20 เบอร์ (หรือเปลี่ยนได้ตามเหมาะสม)
                if (processedCount % 20 == 0 || processedCount == phoneNumbers.size()) {
                    metadata.setProcessedNumbers(processedCount);
                    metadata.setSuccessCount(successCount);
                    metadata.setFailedCount(failedCount);
                    
                    // เก็บ Preview ไว้ 10 แถวแรกเพื่อให้หน้าจอ UI แสดงผลได้เร็ว
                    int previewSize = Math.min(results.size(), 10);
                    metadata.setResultsPreview(new ArrayList<>(results.subList(0, previewSize)));
                    
                    jobManager.saveMetadata(metadata);
                }
            }

            // 4. เขียนผลลัพธ์ทั้งหมดลงไฟล์ Excel ใหม่ (results.xlsx)
            writeResultsToExcel(phoneNumbers, results, targetSystems);

            // 5. ปรับสถานะงานเป็นเสร็จสิ้น (DONE)
            metadata.setStatus("DONE");
            metadata.setCompletedAt(LocalDateTime.now().toString());
            jobManager.saveMetadata(metadata);

        } catch (Exception e) {
            e.printStackTrace();
            metadata.setStatus("FAILED");
            metadata.setErrorMessage(e.getMessage());
            jobManager.saveMetadata(metadata);
        }
    }

    private List<String> readPhoneNumbersFromExcel(java.io.File file) throws Exception {
        List<String> numbers = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                Cell cell = row.getCell(0); // สมมุติว่าเบอร์อยู่คอลัมน์แรก (A)
                if (cell != null) {
                    String val = cell.toString().trim();
                    // ล้างข้อมูลเพื่อแปลงเป็นรูปแบบมาตรฐาน เช่น ลบช่องว่าง/ขีดออก
                    val = val.replaceAll("[^0-9]", "");
                    if (!val.isEmpty()) {
                        numbers.add(val);
                    }
                }
            }
        }
        return numbers;
    }

    private void writeResultsToExcel(List<String> numbers, List<CheckResultRow> results, String[] systems) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Result");
            
            // สร้าง Header
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("เบอร์โทรศัพท์");
            for (int i = 0; i < systems.length; i++) {
                header.createCell(i + 1).setCellValue(systems[i].toUpperCase());
            }

            // เขียนข้อมูล
            for (int r = 0; r < results.size(); r++) {
                Row row = sheet.createRow(r + 1);
                CheckResultRow resRow = results.get(r);
                row.createCell(0).setCellValue(resRow.getPhoneNumber());
                
                for (int s = 0; s < systems.length; s++) {
                    String status = resRow.getSystems().get(systems[s]);
                    row.createCell(s + 1).setCellValue(status);
                }
            }

            // เขียนไฟล์ออกไปที่ Disk
            try (FileOutputStream fos = new FileOutputStream(jobManager.getResultsExcelFile())) {
                workbook.write(fos);
            }
        }
    }

    // ตัวอย่างการเรียก HTTP Client เชื่อมต่อไปยัง Intranet API ปลายทาง
    private String callIntranetApi(String systemCode, String phone) throws Exception {
        // จำลองการต่อ API (ในระบบจริงให้ใช้ Apache HttpClient หรือ HttpURLConnection)
        // เพื่อเช็คสถานะเบอร์และตอบกลับเป็น "USED" หรือ "NOT_USED"
        Thread.sleep(100); // จำลอง network delay 100ms
        
        if (Math.random() < 0.05) {
            throw new RuntimeException("API Connection Timeout"); // จำลองเหตุการณ์ Error
        }
        
        return Math.random() > 0.5 ? "USED" : "NOT_USED";
    }
}
```
