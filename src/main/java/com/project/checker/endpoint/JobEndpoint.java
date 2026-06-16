package com.project.checker.endpoint;

import com.project.checker.model.JobMetadata;
import com.project.checker.service.JobManager;
import com.project.checker.service.PhoneCheckProcessor;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/job")
public class JobEndpoint {

    private static final Logger LOGGER = Logger.getLogger(JobEndpoint.class.getName());

    @Inject
    private JobManager jobManager;

    @Inject
    private PhoneCheckProcessor checkProcessor;

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces("text/plain;charset=UTF-8")
    public Response uploadFile(MultipartFormDataInput input) {
        try {
            Map<String, List<InputPart>> uploadForm = input.getFormDataMap();

            // Force RESTEasy to use UTF-8 for all text parts (fixes Thai/non-ASCII encoding)
            javax.ws.rs.core.MediaType utf8TextPlain = MediaType.valueOf("text/plain; charset=UTF-8");

            // 1. Extract file part
            List<InputPart> fileParts = uploadForm.get("file");
            if (fileParts == null || fileParts.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity("ไฟล์แนบสูญหายหรือไม่ถูกต้อง").build();
            }
            InputPart filePart = fileParts.get(0);
            InputStream inputStream = filePart.getBody(InputStream.class, null);

            // Extract original file name from 'fileNameB64' form field (Base64-encoded UTF-8, bypasses all JBoss charset bugs)
            String fileName = null;
            List<InputPart> fileNameParts = uploadForm.get("fileNameB64");
            if (fileNameParts != null && !fileNameParts.isEmpty()) {
                try {
                    String b64 = fileNameParts.get(0).getBodyAsString().trim();
                    byte[] decoded = java.util.Base64.getDecoder().decode(b64);
                    fileName = new String(decoded, StandardCharsets.UTF_8);
                    LOGGER.log(Level.INFO, "fileName decoded from Base64: {0}", fileName);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to decode fileNameB64", e);
                }
            }
            if (fileName == null || fileName.isEmpty() || fileName.contains("?")) {
                fileName = parseFileName(filePart.getHeaders());
            }
            LOGGER.log(Level.INFO, "Received file upload request. File Name: {0}", fileName);

            // 2. Extract target systems selection
            List<InputPart> systemParts = uploadForm.get("systems");
            if (systemParts == null || systemParts.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity("กรุณาเลือกระบบสำหรับใช้ในการตรวจสอบอย่างน้อย 1 ระบบ").build();
            }
            String targetSystemsStr = systemParts.get(0).getBodyAsString();
            if (targetSystemsStr == null || targetSystemsStr.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity("กรุณาเลือกระบบสำหรับใช้ในการตรวจสอบอย่างน้อย 1 ระบบ").build();
            }
            String[] targetSystems = targetSystemsStr.split(",");

            // 3. Clear existing active job files and write uploaded stream to input file
            jobManager.clearActiveJob();
            Files.copy(inputStream, jobManager.getInputExcelFile().toPath(), StandardCopyOption.REPLACE_EXISTING);

            // 4. Initialize job metadata
            JobMetadata metadata = new JobMetadata();
            metadata.setJobId(UUID.randomUUID().toString());
            metadata.setStatus("RUNNING");
            metadata.setFileName(fileName);
            metadata.setUploadedAt(LocalDateTime.now().toString());
            metadata.setProcessedNumbers(0);
            metadata.setSuccessCount(0);
            metadata.setFailedCount(0);
            jobManager.saveMetadata(metadata);

            // 5. Trigger asynchronous processing thread pool
            checkProcessor.startProcessing(targetSystems);

            return Response.status(Response.Status.ACCEPTED).entity("อัปโหลดไฟล์สำเร็จ ระบบกำลังเริ่มตรวจสอบข้อมูลในเบื้องหลัง").build();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error uploading file", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("เกิดข้อผิดพลาดภายในระบบ: " + e.getMessage()).build();
        }
    }

    @POST
    @Path("/single")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces("text/plain;charset=UTF-8")
    public Response checkSingleNumber(
            @FormParam("phoneNumber") String phoneNumber,
            @FormParam("systems") String targetSystemsStr) {
        try {
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity("กรุณากรอกหมายเลขโทรศัพท์").build();
            }
            if (targetSystemsStr == null || targetSystemsStr.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity("กรุณาเลือกระบบสำหรับใช้ในการตรวจสอบอย่างน้อย 1 ระบบ").build();
            }
            String[] targetSystems = targetSystemsStr.split(",");

            // Clear existing active job
            jobManager.clearActiveJob();

            // Write phone number to input file as plain text (simulate CSV/TXT upload)
            String content = "MSISDN\n" + phoneNumber.trim() + "\n";
            java.nio.file.Files.write(jobManager.getInputExcelFile().toPath(), content.getBytes(StandardCharsets.UTF_8));

            // Initialize job metadata
            JobMetadata metadata = new JobMetadata();
            metadata.setJobId(UUID.randomUUID().toString());
            metadata.setStatus("RUNNING");
            metadata.setFileName("single_check_" + phoneNumber.trim() + ".txt");
            metadata.setUploadedAt(LocalDateTime.now().toString());
            metadata.setProcessedNumbers(0);
            metadata.setSuccessCount(0);
            metadata.setFailedCount(0);
            jobManager.saveMetadata(metadata);

            // Trigger asynchronous processing
            checkProcessor.startProcessing(targetSystems);

            return Response.status(Response.Status.ACCEPTED).entity("เริ่มตรวจสอบหมายเลขเดี่ยวสำเร็จ").build();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error initiating single check", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("เกิดข้อผิดพลาดภายในระบบ: " + e.getMessage()).build();
        }
    }

    @GET
    @Path("/status")
    @Produces("application/json;charset=UTF-8")
    public Response getStatus() {
        JobMetadata metadata = jobManager.getMetadata();
        if (metadata == null) {
            return Response.status(Response.Status.NO_CONTENT).build(); // HTTP 204
        }
        // Manually serialize to ensure UTF-8 encoding for Thai characters
        javax.json.bind.Jsonb jsonb = javax.json.bind.JsonbBuilder.create();
        String json = jsonb.toJson(metadata);
        return Response.ok(json)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .type("application/json;charset=UTF-8")
                .build();
    }

    @GET
    @Path("/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadFile() {
        JobMetadata metadata = jobManager.getMetadata();
        if (metadata == null || !"DONE".equals(metadata.getStatus())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("ไม่มีไฟล์ผลลัพธ์หรือประมวลผลยังไม่เสร็จสิ้น").build();
        }

        java.io.File file = jobManager.getResultsExcelFile();
        if (!file.exists()) {
            return Response.status(Response.Status.NOT_FOUND).entity("ไม่พบไฟล์ผลลัพธ์บนเซิร์ฟเวอร์").build();
        }

        // Sanitize output filename
        String originalName = metadata.getFileName();
        String extension = "xlsx";
        String baseName = "results";
        if (originalName != null) {
            int dotIdx = originalName.lastIndexOf('.');
            if (dotIdx > 0) {
                baseName = originalName.substring(0, dotIdx);
                extension = originalName.substring(dotIdx + 1);
            } else {
                baseName = originalName;
            }
        }
        String outputFileName = "results_" + baseName + "." + extension;

        Response.ResponseBuilder response = Response.ok(file);
        try {
            String fallbackFileName = outputFileName.replaceAll("[^\\x20-\\x7E]", "_");
            String encodedFileName = URLEncoder.encode(outputFileName, "UTF-8").replaceAll("\\+", "%20");
            response.header("Content-Disposition", "attachment; filename=\"" + fallbackFileName + "\"; filename*=UTF-8''" + encodedFileName);
        } catch (UnsupportedEncodingException e) {
            response.header("Content-Disposition", "attachment; filename=\"results.xlsx\"");
        }
        return response.build();
    }

    private String parseFileName(MultivaluedMap<String, String> headers) {
        String contentDisposition = headers.getFirst("Content-Disposition");
        if (contentDisposition != null) {
            String[] tokens = contentDisposition.split(";");
            // First check RFC 5987 filename*
            for (String token : tokens) {
                String trimmed = token.trim();
                if (trimmed.startsWith("filename*")) {
                    String[] parts = trimmed.split("=");
                    if (parts.length > 1) {
                        String rawVal = parts[1].trim();
                        if (rawVal.toLowerCase().startsWith("utf-8''")) {
                            try {
                                String encoded = rawVal.substring(7);
                                return URLDecoder.decode(encoded, "UTF-8");
                            } catch (Exception e) {
                                // ignore and fallback
                            }
                        }
                    }
                }
            }

            // Fallback to standard filename
            for (String token : tokens) {
                String trimmed = token.trim();
                if (trimmed.startsWith("filename") && !trimmed.startsWith("filename*")) {
                    String[] name = trimmed.split("=");
                    if (name.length > 1) {
                        String rawName = name[1].trim().replaceAll("\"", "");
                        try {
                            return new String(rawName.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            return rawName;
                        }
                    }
                }
            }
        }
        return "uploaded_file.xlsx";
    }
}
