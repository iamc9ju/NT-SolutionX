package com.project.checker.endpoint;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Path("/master")
public class MasterNumberEndpoint {

    private static final Logger LOGGER = Logger.getLogger(MasterNumberEndpoint.class.getName());

    @Inject
    private com.project.checker.service.ExternalSystemClient systemClient;

    @javax.annotation.Resource(lookup = "java:comp/DefaultManagedExecutorService")
    private java.util.concurrent.ExecutorService executor;

    private static String cachedMasterListJson = null;
    private static long cacheExpiry = 0;
    private static final long CACHE_TTL_MS = 30000; // 30 seconds
    private static final Object CACHE_LOCK = new Object();

    public static void invalidateCache() {
        synchronized (CACHE_LOCK) {
            cachedMasterListJson = null;
            cacheExpiry = 0;
            LOGGER.info("Backend master number cache invalidated.");
        }
    }

    private Connection getConnection() throws Exception {
        String jndiName = System.getProperty("master.datasource.jndi");
        if (jndiName == null || jndiName.trim().isEmpty()) {
            jndiName = System.getenv("MASTER_DATASOURCE_JNDI");
        }
        if (jndiName == null || jndiName.trim().isEmpty()) {
            jndiName = "java:/OMDS";
        }
        try {
            javax.naming.InitialContext ctx = new javax.naming.InitialContext();
            javax.sql.DataSource ds = (javax.sql.DataSource) ctx.lookup(jndiName.trim());
            return ds.getConnection();
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "JNDI lookup failed for " + jndiName + " (" + e.getMessage() + "), falling back to direct JDBC connection.");
            String dbUrl = System.getProperty("master.datasource.url");
            if (dbUrl == null || dbUrl.trim().isEmpty()) {
                dbUrl = "jdbc:oracle:thin:@10.36.1.51:1521:OMDB";
            }
            String dbUser = System.getProperty("master.datasource.username");
            if (dbUser == null || dbUser.trim().isEmpty()) {
                dbUser = "omuser";
            }
            String dbPass = System.getProperty("master.datasource.password");
            if (dbPass == null || dbPass.trim().isEmpty()) {
                dbPass = "xjfeil92";
            }
            Class.forName("oracle.jdbc.OracleDriver");
            return java.sql.DriverManager.getConnection(dbUrl, dbUser, dbPass);
        }
    }

    @GET
    @Path("/list")
    @Produces("application/json;charset=UTF-8")
    public Response getMasterNumbers(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("10") int limit,
            @QueryParam("search") String search,
            @QueryParam("status") String status) {

        if (page < 1) page = 1;
        if (limit < 1 || limit > 100) limit = 10;

        String searchVal = (search != null) ? search.trim() : "";
        String statusVal = (status != null) ? status.trim() : "";
        boolean isSimpleQuery = searchVal.isEmpty() && (statusVal.isEmpty() || "all".equalsIgnoreCase(statusVal));

        int maxRow = page * limit;
        int minRow = (page - 1) * limit;

        int totalRecords = 0;
        JsonArrayBuilder arrBuilder = Json.createArrayBuilder();

        try (Connection conn = getConnection()) {
            // 1. Get total count
            if (isSimpleQuery) {
                // Extremely fast count using the last sync log estimate or fallback to 12000000 (takes ~10ms)
                String countSql = "SELECT NVL((" +
                        "  SELECT TOTAL_RECORDS " +
                        "  FROM NV_SYNC_LOG " +
                        "  WHERE SYNC_STATUS = 'SUCCESS' " +
                        "  ORDER BY LOG_ID DESC " +
                        "  FETCH FIRST 1 ROWS ONLY" +
                        "), 12000000) + (SELECT COUNT(*) FROM NV_LOCAL_NUMBER_REGISTRY) FROM DUAL";
                try (PreparedStatement countStmt = conn.prepareStatement(countSql);
                     ResultSet rs = countStmt.executeQuery()) {
                    if (rs.next()) {
                        totalRecords = rs.getInt(1);
                    }
                }
            } else {
                // Query counts dynamically from the view
                StringBuilder countSql = new StringBuilder("SELECT COUNT(1) FROM NV_NUMBER_REGISTRY WHERE 1=1 ");
                List<Object> countParams = new ArrayList<>();
                if (!searchVal.isEmpty()) {
                    countSql.append("AND MSISDN LIKE ? ");
                    countParams.add(searchVal + "%");
                }
                if (!statusVal.isEmpty() && !"all".equalsIgnoreCase(statusVal)) {
                    countSql.append("AND STATUS = ? ");
                    countParams.add(statusVal.toUpperCase());
                }
                try (PreparedStatement countStmt = conn.prepareStatement(countSql.toString())) {
                    for (int i = 0; i < countParams.size(); i++) {
                        countStmt.setObject(i + 1, countParams.get(i));
                    }
                    try (ResultSet rs = countStmt.executeQuery()) {
                        if (rs.next()) {
                            totalRecords = rs.getInt(1);
                        }
                    }
                }
            }

            // 2. Get paginated data from NV_NUMBER_REGISTRY
            StringBuilder dataSql = new StringBuilder();
            dataSql.append("SELECT * FROM (");
            dataSql.append("  SELECT a.*, ROWNUM rnum FROM (");
            dataSql.append("    SELECT ID, MSISDN, ICCID, IMSI, SERVICE_TYPE, OWNER, STATUS, UPDATED_AT, REMARKS ");
            dataSql.append("    FROM NV_NUMBER_REGISTRY ");
            dataSql.append("    WHERE 1=1 ");
            
            List<Object> dataParams = new ArrayList<>();
            if (!searchVal.isEmpty()) {
                dataSql.append("    AND MSISDN LIKE ? ");
                dataParams.add(searchVal + "%");
            }
            if (!statusVal.isEmpty() && !"all".equalsIgnoreCase(statusVal)) {
                dataSql.append("    AND STATUS = ? ");
                dataParams.add(statusVal.toUpperCase());
            }
            dataSql.append("    ORDER BY ID DESC ");
            dataSql.append("  ) a WHERE ROWNUM <= ? ");
            dataSql.append(") WHERE rnum > ? ");

            dataParams.add(maxRow);
            dataParams.add(minRow);

            try (PreparedStatement dataStmt = conn.prepareStatement(dataSql.toString())) {
                for (int i = 0; i < dataParams.size(); i++) {
                    dataStmt.setObject(i + 1, dataParams.get(i));
                }
                try (ResultSet rs = dataStmt.executeQuery()) {
                    while (rs.next()) {
                        String msisdn = rs.getString("MSISDN");
                        String iccid = rs.getString("ICCID");
                        String imsi = rs.getString("IMSI");
                        String serviceType = rs.getString("SERVICE_TYPE");
                        String owner = rs.getString("OWNER");
                        String statusStr = rs.getString("STATUS");
                        Timestamp updatedAt = rs.getTimestamp("UPDATED_AT");
                        String remarks = rs.getString("REMARKS");

                        String timeStr = updatedAt != null ? updatedAt.toString() : "";

                        JsonObjectBuilder objBuilder = Json.createObjectBuilder()
                                .add("phoneNumber", msisdn != null ? msisdn : "")
                                .add("iccid", iccid != null ? iccid : "")
                                .add("imsi", imsi != null ? imsi : "")
                                .add("category", serviceType != null ? serviceType : "Prepaid")
                                .add("source", owner != null ? owner : "")
                                .add("status", statusStr != null ? statusStr : "AVAILABLE")
                                .add("lastSync", timeStr)
                                .add("remarks", remarks != null ? remarks : "");
                        arrBuilder.add(objBuilder);
                    }
                }
            }

            JsonObject responseJson = Json.createObjectBuilder()
                    .add("total", totalRecords)
                    .add("page", page)
                    .add("limit", limit)
                    .add("data", arrBuilder)
                    .build();

            return Response.ok(responseJson.toString())
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .type("application/json;charset=UTF-8")
                    .build();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve master numbers list from NV_NUMBER_REGISTRY", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Json.createObjectBuilder().add("error", e.getMessage()).build().toString())
                    .build();
        }
    }

    @GET
    @Path("/inspect-realtime")
    @Produces("application/json;charset=UTF-8")
    public Response inspectRealtime(@QueryParam("phoneNumber") String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Json.createObjectBuilder().add("error", "กรุณาระบุหมายเลขโทรศัพท์").build().toString())
                    .build();
        }

        String phone = phoneNumber.trim();
        String[] targetSystems = {"ocs_ocs", "wom", "wom_iot", "billing", "crm", "brm", "inventory"};

        final Map<String, String> systemResults = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, String> rawResponses = new java.util.concurrent.ConcurrentHashMap<>();

        // Pre-populate timeouts
        for (String sys : targetSystems) {
            systemResults.put(sys, "TIMEOUT");
            rawResponses.put(sys, "Connection Timed Out");
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String sys : targetSystems) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    String status = systemClient.checkPhoneNumber(sys, phone);
                    systemResults.put(sys, status);
                    rawResponses.put(sys, "Success");
                } catch (Exception e) {
                    systemResults.put(sys, "ERROR");
                    rawResponses.put(sys, e.getMessage() != null ? e.getMessage() : e.toString());
                }
            }, executor);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Realtime parallel check timed out for phone: " + phone, e);
        }

        JsonObjectBuilder sysBuilder = Json.createObjectBuilder();
        JsonObjectBuilder respBuilder = Json.createObjectBuilder();

        for (String sys : targetSystems) {
            sysBuilder.add(sys, systemResults.get(sys));
            respBuilder.add(sys, rawResponses.get(sys));
        }

        JsonObject result = Json.createObjectBuilder()
                .add("phoneNumber", phone)
                .add("systems", sysBuilder)
                .add("rawResponses", respBuilder)
                .build();

        return Response.ok(result.toString())
                .type("application/json;charset=UTF-8")
                .build();
    }

    @GET
    @Path("/inspect")
    @Produces("application/json;charset=UTF-8")
    public Response inspectDb() {
        JsonObjectBuilder info = Json.createObjectBuilder();
        try (Connection conn = getConnection()) {
            JsonArrayBuilder cols = Json.createArrayBuilder();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT column_name, data_type FROM user_tab_columns WHERE table_name = 'OM_MASTER_DATA' ORDER BY column_id");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    cols.add(Json.createObjectBuilder()
                        .add("column", rs.getString("column_name") != null ? rs.getString("column_name") : "")
                        .add("type", rs.getString("data_type") != null ? rs.getString("data_type") : "")
                    );
                }
            }
            info.add("om_master_data_columns", cols);
            
            int count = 0;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM OM_MASTER_DATA");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) count = rs.getInt(1);
            }
            info.add("om_master_data_count", count);

            JsonArrayBuilder samples = Json.createArrayBuilder();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM OM_MASTER_DATA WHERE ROWNUM <= 5");
                 ResultSet rs = stmt.executeQuery()) {
                java.sql.ResultSetMetaData rsMeta = rs.getMetaData();
                int colCount = rsMeta.getColumnCount();
                while (rs.next()) {
                    JsonObjectBuilder rowBuilder = Json.createObjectBuilder();
                    for (int i = 1; i <= colCount; i++) {
                        String name = rsMeta.getColumnName(i);
                        String val = rs.getString(i);
                        rowBuilder.add(name, val != null ? val : "null");
                    }
                    samples.add(rowBuilder);
                }
            }
            info.add("om_master_data_samples", samples);

            return Response.ok(info.build().toString()).build();
        } catch (Exception e) {
            return Response.status(500).entity(e.getMessage()).build();
        }
    }
    @POST
    @Path("/sync")
    @Produces("application/json;charset=UTF-8")
    public Response syncFromOm() {
        invalidateCache(); // Clear cache at start of sync
        LOGGER.info("Starting manual synchronization check (reconciliation)...");
        
        int omCount = 0;
        int localCount = 0;
        
        try (Connection conn = getConnection()) {
            // Check count of real-time OM numbers via database link
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM INV_MASTER@OM_INVDB_LINK WHERE IS_ACTIVE = 'Y' AND EXTERNAL_ID IS NOT NULL AND MVNO_ID = '89'")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        omCount = rs.getInt(1);
                    }
                }
            }
            
            // Log sync success to NV_SYNC_LOG
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO NV_SYNC_LOG (TOTAL_RECORDS, SYNCED_RECORDS, FAILED_RECORDS, SYNC_STATUS, ERROR_MESSAGE) VALUES (?, ?, 0, 'SUCCESS', ?)")) {
                stmt.setInt(1, omCount);
                stmt.setInt(2, omCount);
                stmt.setString(3, "Manual synchronization check completed. OM count: " + omCount);
                stmt.executeUpdate();
            }
            
            return Response.ok(Json.createObjectBuilder()
                    .add("success", true)
                    .add("message", "ซิงค์ข้อมูลเสร็จสมบูรณ์! ตรวจพบเบอร์โทรศัพท์ในระบบหลัก OM (Real-time View) ทั้งหมด " + omCount + " รายการ")
                    .build().toString())
                    .build();
                    
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed sync verification", e);
            
            // Log sync failure
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO NV_SYNC_LOG (TOTAL_RECORDS, SYNCED_RECORDS, FAILED_RECORDS, SYNC_STATUS, ERROR_MESSAGE) VALUES (0, 0, 0, 'FAILED', ?)")) {
                stmt.setString(1, "Sync check failed: " + e.getMessage());
                stmt.executeUpdate();
            } catch (Exception logEx) {
                LOGGER.log(Level.SEVERE, "Failed to log sync error", logEx);
            }
            
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Json.createObjectBuilder()
                            .add("success", false)
                            .add("error", "เกิดข้อผิดพลาดขณะตรวจสอบการซิงค์ข้อมูล: " + e.getMessage())
                            .build().toString())
                    .build();
        }
    }

    @GET
    @Path("/sync-status")
    @Produces("application/json;charset=UTF-8")
    public Response getSyncStatus() {
        int totalMaster = 0;
        String lastSyncRun = "ยังไม่ได้ซิงค์ข้อมูล";
        int errorCount = 0;
        
        try (Connection conn = getConnection()) {
            // 1. Get total records in view using optimized fast estimate (takes ~10ms instead of 8s full view scan)
            String totalSql = "SELECT NVL((" +
                    "  SELECT TOTAL_RECORDS " +
                    "  FROM NV_SYNC_LOG " +
                    "  WHERE SYNC_STATUS = 'SUCCESS' " +
                    "  ORDER BY LOG_ID DESC " +
                    "  FETCH FIRST 1 ROWS ONLY" +
                    "), 12000000) + (SELECT COUNT(*) FROM NV_LOCAL_NUMBER_REGISTRY) FROM DUAL";
            try (PreparedStatement stmt = conn.prepareStatement(totalSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    totalMaster = rs.getInt(1);
                }
            }
            
            // 2. Get last sync time from NV_SYNC_LOG
            try (PreparedStatement stmt = conn.prepareStatement("SELECT SYNC_TIME, SYNC_STATUS FROM NV_SYNC_LOG ORDER BY LOG_ID DESC")) {
                stmt.setMaxRows(1);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Timestamp syncTime = rs.getTimestamp("SYNC_TIME");
                        String syncStatus = rs.getString("SYNC_STATUS");
                        if (syncTime != null) {
                            lastSyncRun = syncTime.toString() + " (" + syncStatus + ")";
                        }
                    }
                }
            }
            
            // 3. Count failures/mismatches or error logs in NV_SYNC_LOG
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM NV_SYNC_LOG WHERE SYNC_STATUS = 'FAILED'")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        errorCount = rs.getInt(1);
                    }
                }
            }
            
            JsonObject responseJson = Json.createObjectBuilder()
                    .add("totalMaster", totalMaster)
                    .add("syncedCount", totalMaster)
                    .add("errorCount", errorCount)
                    .add("lastSyncRun", lastSyncRun)
                    .build();
                    
            return Response.ok(responseJson.toString()).build();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve sync status", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Json.createObjectBuilder().add("error", e.getMessage()).build().toString())
                    .build();
        }
    }

    @GET
    @Path("/config")
    @Produces("application/json;charset=UTF-8")
    public Response getConfigs() {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT CONFIG_KEY, CONFIG_VALUE FROM NV_SYSTEM_CONFIG");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                builder.add(rs.getString("CONFIG_KEY"), rs.getString("CONFIG_VALUE"));
            }
            return Response.ok(builder.build().toString()).build();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load configs", e);
            return Response.status(500).entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/config")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/json;charset=UTF-8")
    public Response saveConfigs(JsonObject input) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (String key : input.keySet()) {
                    String value = input.getString(key, "");
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE NV_SYSTEM_CONFIG SET CONFIG_VALUE = ? WHERE CONFIG_KEY = ?")) {
                        stmt.setString(1, value);
                        stmt.setString(2, key);
                        stmt.executeUpdate();
                    }
                }
                conn.commit();
                return Response.ok(Json.createObjectBuilder().add("success", true).add("message", "บันทึกการตั้งค่าสำเร็จ").build().toString()).build();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to save configs", e);
            return Response.status(500).entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/register")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/json;charset=UTF-8")
    public Response registerNumber(JsonObject input) {
        try {
            String msisdn = input.getString("msisdn", "").trim();
            String iccid = input.getString("iccid", "").trim();
            String imsi = input.getString("imsi", "").trim();
            String serviceType = input.getString("serviceType", "Prepaid").trim();
            String owner = input.getString("owner", "").trim();
            String status = input.getString("status", "AVAILABLE").trim();
            String remarks = input.getString("remarks", "").trim();

            if (msisdn.isEmpty() || iccid.isEmpty() || imsi.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Json.createObjectBuilder().add("error", "กรุณากรอกข้อมูลที่จำเป็นให้ครบถ้วน (MSISDN, ICCID, IMSI)").build().toString())
                        .build();
            }

            if (!msisdn.matches("\\d+")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Json.createObjectBuilder().add("error", "หมายเลขโทรศัพท์ (MSISDN) ต้องเป็นตัวเลขเท่านั้น").build().toString())
                        .build();
            }
            if (msisdn.length() != 9 && msisdn.length() != 10) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Json.createObjectBuilder().add("error", "หมายเลขโทรศัพท์ (MSISDN) ต้องมีความยาว 9 หรือ 10 หลัก").build().toString())
                        .build();
            }

            if (!iccid.matches("\\d+")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Json.createObjectBuilder().add("error", "รหัสซิมการ์ด (ICCID) ต้องเป็นตัวเลขเท่านั้น").build().toString())
                        .build();
            }
            if (iccid.length() != 19 && iccid.length() != 20) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Json.createObjectBuilder().add("error", "รหัสซิมการ์ด (ICCID) ต้องมีความยาว 19 หรือ 20 หลัก").build().toString())
                        .build();
            }

            if (!imsi.matches("\\d+")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Json.createObjectBuilder().add("error", "รหัสเครือข่าย (IMSI) ต้องเป็นตัวเลขเท่านั้น").build().toString())
                        .build();
            }
            if (imsi.length() != 15) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Json.createObjectBuilder().add("error", "รหัสเครือข่าย (IMSI) ต้องมีความยาว 15 หลัก").build().toString())
                        .build();
            }

            try (Connection conn = getConnection()) {

                // 2. Insert record
                try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO NV_LOCAL_NUMBER_REGISTRY (MSISDN, ICCID, IMSI, SERVICE_TYPE, OWNER, STATUS, REMARKS) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    insertStmt.setString(1, msisdn);
                    insertStmt.setString(2, iccid);
                    insertStmt.setString(3, imsi);
                    if (serviceType.isEmpty()) {
                        insertStmt.setNull(4, java.sql.Types.VARCHAR);
                    } else {
                        insertStmt.setString(4, serviceType);
                    }
                    if (owner.isEmpty()) {
                        insertStmt.setNull(5, java.sql.Types.VARCHAR);
                    } else {
                        insertStmt.setString(5, owner);
                    }
                    insertStmt.setString(6, status);
                    if (remarks.isEmpty()) {
                        insertStmt.setNull(7, java.sql.Types.VARCHAR);
                    } else {
                        insertStmt.setString(7, remarks);
                    }

                    insertStmt.executeUpdate();
                }
            }

            invalidateCache();
            return Response.ok(Json.createObjectBuilder().add("success", true).add("message", "ลงทะเบียนหมายเลขสำเร็จ").build().toString())
                    .build();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to register new master number", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Json.createObjectBuilder().add("error", "เกิดข้อผิดพลาดในการบันทึกข้อมูล: " + e.getMessage()).build().toString())
                    .build();
        }
    }

    @GET
    @Path("/template")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response downloadTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Master Number Template");

            // Setup Header Row Style
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Columns (Exact database mapping - Owner and Status removed)
            String[] headers = {"MSISDN (เบอร์โทรศัพท์)*", "ICCID (เลขซิมการ์ด)*", "IMSI (เลขเครือข่าย)*", "Service Type (Prepaid/Postpaid)", "Remarks (หมายเหตุ)"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Example Row (Owner and Status removed)
            Row dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue("190170020");
            dataRow.createCell(1).setCellValue("89660023011200000011");
            dataRow.createCell(2).setCellValue("520001919998336");
            dataRow.createCell(3).setCellValue("Prepaid");
            dataRow.createCell(4).setCellValue("ตัวอย่างข้อมูลจำลอง");

            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);

            return Response.ok(out.toByteArray())
                    .header("Content-Disposition", "attachment; filename=\"Master_Number_Template.xlsx\"")
                    .build();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to generate Excel template", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error generating template: " + e.getMessage())
                    .build();
        }
    }

    @POST
    @Path("/import")
    @Consumes("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Produces("application/json;charset=UTF-8")
    public Response importExcel(InputStream excelStream) {
        DataFormatter formatter = new DataFormatter();
        List<String> validationErrors = new ArrayList<>();
        List<ExcelRecord> records = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(excelStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Json.createObjectBuilder().add("error", "ไฟล์ Excel ไม่มีหน้าข้อมูล").build().toString())
                        .build();
            }

            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            if (lastRow < 1) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Json.createObjectBuilder().add("error", "ไฟล์ Excel ไม่มีข้อมูลรายการนำเข้า (มีเพียงแถวหัวตารางหรือว่างเปล่า)").build().toString())
                        .build();
            }

            Set<String> msisdnsInSheet = new HashSet<>();
            Set<String> iccidsInSheet = new HashSet<>();
            Set<String> imsisInSheet = new HashSet<>();

            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row, formatter)) {
                    continue;
                }

                String msisdn = getCellValue(row.getCell(0), formatter).replaceAll("\\s+", "");
                String iccid = getCellValue(row.getCell(1), formatter).replaceAll("\\s+", "");
                String imsi = getCellValue(row.getCell(2), formatter).replaceAll("\\s+", "");
                String serviceType = getCellValue(row.getCell(3), formatter).trim();
                String owner = ""; // Removed from Excel, default to empty string so database gets NULL
                String status = "AVAILABLE"; // Removed from Excel, default to AVAILABLE
                String remarks = getCellValue(row.getCell(4), formatter).trim();

                int humanRowIdx = r + 1;

                if (msisdn.isEmpty() || iccid.isEmpty() || imsi.isEmpty()) {
                    validationErrors.add("แถวที่ " + humanRowIdx + ": ข้อมูลที่จำเป็นขาดหายไป (MSISDN, ICCID, IMSI ห้ามว่าง)");
                    continue;
                }

                // Format Validations
                if (!msisdn.matches("\\d+")) {
                    validationErrors.add("แถวที่ " + humanRowIdx + ": หมายเลขโทรศัพท์ (MSISDN) ต้องเป็นตัวเลขเท่านั้น (พบ: " + msisdn + ")");
                } else if (msisdn.length() != 9 && msisdn.length() != 10) {
                    validationErrors.add("แถวที่ " + humanRowIdx + ": หมายเลขโทรศัพท์ (MSISDN) ต้องมีความยาว 9 หรือ 10 หลัก (ยาว " + msisdn.length() + " หลัก)");
                }

                if (!iccid.matches("\\d+")) {
                    validationErrors.add("แถวที่ " + humanRowIdx + ": รหัสซิมการ์ด (ICCID) ต้องเป็นตัวเลขเท่านั้น (พบ: " + iccid + ")");
                } else if (iccid.length() != 19 && iccid.length() != 20) {
                    validationErrors.add("แถวที่ " + humanRowIdx + ": รหัสซิมการ์ด (ICCID) ต้องมีความยาว 19 หรือ 20 หลัก (ยาว " + iccid.length() + " หลัก)");
                }

                if (!imsi.matches("\\d+")) {
                    validationErrors.add("แถวที่ " + humanRowIdx + ": รหัสเครือข่าย (IMSI) ต้องเป็นตัวเลขเท่านั้น (พบ: " + imsi + ")");
                } else if (imsi.length() != 15) {
                    validationErrors.add("แถวที่ " + humanRowIdx + ": รหัสเครือข่าย (IMSI) ต้องมีความยาว 15 หลัก (ยาว " + imsi.length() + " หลัก)");
                }

                // Sheet Duplicate Checks removed (non-unique constraints)

                // Status sanitization
                if (status.isEmpty()) {
                    status = "AVAILABLE";
                } else {
                    status = status.toUpperCase();
                    if (!status.equals("AVAILABLE") && !status.equals("ACTIVE") && !status.equals("RESERVED") && !status.equals("SUSPENDED")) {
                        validationErrors.add("แถวที่ " + humanRowIdx + ": สถานะ (Status) ไม่ถูกต้อง ต้องเป็น AVAILABLE, ACTIVE, RESERVED, หรือ SUSPENDED");
                    }
                }

                // Service type sanitization
                if (!serviceType.isEmpty()) {
                    if (serviceType.equalsIgnoreCase("prepaid")) {
                        serviceType = "Prepaid";
                    } else if (serviceType.equalsIgnoreCase("postpaid")) {
                        serviceType = "Postpaid";
                    }
                }

                records.add(new ExcelRecord(msisdn, iccid, imsi, serviceType, owner, status, remarks));
            }

            if (!validationErrors.isEmpty()) {
                JsonArrayBuilder arr = Json.createArrayBuilder();
                for (String err : validationErrors) {
                    arr.add(err);
                }
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Json.createObjectBuilder()
                                .add("success", false)
                                .add("error", "พบข้อมูลรูปแบบไม่ถูกต้องในไฟล์ Excel")
                                .add("details", arr)
                                .build().toString())
                        .build();
            }

            // Validate against database and execute inside transaction
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    for (int i = 0; i < records.size(); i++) {
                        ExcelRecord rec = records.get(i);
                        int humanRowIdx = i + 2; // Rows start at 2 (Row 1 is header)

                        // DB Duplicate Check removed (non-unique constraints)

                        // Insert statement
                        try (PreparedStatement insertStmt = conn.prepareStatement(
                                "INSERT INTO NV_LOCAL_NUMBER_REGISTRY (MSISDN, ICCID, IMSI, SERVICE_TYPE, OWNER, STATUS, REMARKS) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                            insertStmt.setString(1, rec.msisdn);
                            insertStmt.setString(2, rec.iccid);
                            insertStmt.setString(3, rec.imsi);
                            if (rec.serviceType.isEmpty()) {
                                insertStmt.setNull(4, java.sql.Types.VARCHAR);
                            } else {
                                insertStmt.setString(4, rec.serviceType);
                            }
                            if (rec.owner.isEmpty()) {
                                insertStmt.setNull(5, java.sql.Types.VARCHAR);
                            } else {
                                insertStmt.setString(5, rec.owner);
                            }
                            insertStmt.setString(6, rec.status);
                            if (rec.remarks.isEmpty()) {
                                insertStmt.setNull(7, java.sql.Types.VARCHAR);
                            } else {
                                insertStmt.setString(7, rec.remarks);
                            }
                            insertStmt.executeUpdate();
                        }
                    }

                    conn.commit();
                    invalidateCache();
                    return Response.ok(Json.createObjectBuilder()
                                    .add("success", true)
                                    .add("message", "นำเข้าข้อมูลจาก Excel ทั้งหมด " + records.size() + " รายการสำเร็จ")
                                    .build().toString())
                            .build();

                } catch (Exception ex) {
                    conn.rollback();
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Json.createObjectBuilder()
                                    .add("success", false)
                                    .add("error", "นำเข้าล้มเหลวเนื่องจากข้อมูลขัดแย้ง: " + ex.getMessage())
                                    .build().toString())
                            .build();
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to import Excel spreadsheet", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Json.createObjectBuilder().add("error", "เกิดข้อผิดพลาดภายในระบบขณะประมวลผลไฟล์: " + e.getMessage()).build().toString())
                    .build();
        }
    }

    private String getCellValue(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private boolean isRowEmpty(Row row, DataFormatter formatter) {
        if (row == null) {
            return true;
        }
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK && !getCellValue(cell, formatter).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @DELETE
    @Path("/terminate/{msisdn}")
    @Produces("application/json;charset=UTF-8")
    public Response terminateNumber(@PathParam("msisdn") String msisdn, @QueryParam("force") @DefaultValue("false") boolean force) {
        if (msisdn == null || msisdn.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Json.createObjectBuilder().add("error", "กรุณาระบุหมายเลขโทรศัพท์ (MSISDN)").build().toString())
                    .build();
        }

        String trimmedMsisdn = msisdn.trim();
        String omUrl = System.getProperty("checker.api.url.om_terminate");
        if (omUrl == null || omUrl.trim().isEmpty()) {
            omUrl = System.getenv("OM_TERMINATE_API_URL");
        }
        if (omUrl == null || omUrl.trim().isEmpty()) {
            omUrl = "http://10.36.1.48:8080/omapi/api/numberreturnin/updateinv/msisdn";
        }

        LOGGER.log(Level.INFO, "TERMINATE REQUEST - START: MSISDN={0}, Force={1}, OM_URL={2}", new Object[]{trimmedMsisdn, force, omUrl});

        boolean omSuccess = false;
        String omMessage = "";

        if (force) {
            omSuccess = true;
            omMessage = "ข้ามการยิงคำสั่งระบบ OM ด้วยสิทธิ์บังคับลบ (Force Delete Locally)";
        } else if ("MOCK".equalsIgnoreCase(omUrl)) {
            omSuccess = true;
            omMessage = "[MOCK] จำลองส่งคำสั่ง Terminate ไปยังระบบ OM สำเร็จ";
        } else {
            // Call OM Return In API
            try {
                Client client = ClientBuilder.newBuilder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build();

                JsonObject requestBody = Json.createObjectBuilder()
                        .add("external_id", trimmedMsisdn)
                        .add("mvno_id", "89")
                        .add("operator_id", "0")
                        .add("zone_id", "1")
                        .add("number_type", "1")
                        .add("digit", 2)
                        .add("inv_master_profile", Json.createObjectBuilder()
                                .add("lucky_number", "0")
                                .add("lucky_number_level", 0)
                                .add("non_charge", "1")
                                .add("owner", 2)
                                .add("payment_mode", 1)
                                .add("sale_chanel", 7)
                                .add("status", 15)
                                .add("flag_vip", 0))
                        .build();

                try (Response response = client.target(omUrl)
                        .request(MediaType.APPLICATION_JSON)
                        .header("creater", "number-checker")
                        .header("extn_id_type", "201")
                        .post(Entity.json(requestBody))) {

                    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                        String entityStr = response.readEntity(String.class);
                        try (javax.json.JsonReader reader = Json.createReader(new java.io.StringReader(entityStr))) {
                            JsonObject resObj = reader.readObject();
                            String statusCode = resObj.containsKey("status_code") ? resObj.getString("status_code") : "";
                            String statusMessage = resObj.containsKey("status_message") ? resObj.getString("status_message") : "";
                            if ("200".equals(statusCode) || "success".equalsIgnoreCase(statusMessage) || entityStr.contains("success")) {
                                omSuccess = true;
                                omMessage = "ระบบ OM ตอบรับการยกเลิกบริการเบอร์สำเร็จ" + (statusMessage.isEmpty() ? "" : " (" + statusMessage + ")");
                            } else {
                                omMessage = "ระบบ OM ตอบกลับข้อผิดพลาด (Code " + statusCode + "): " + statusMessage;
                            }
                        } catch (Exception ex) {
                            if (entityStr.toLowerCase().contains("success")) {
                                omSuccess = true;
                                omMessage = "ระบบ OM ตอบรับการยกเลิกบริการเบอร์สำเร็จ (Parsing Fallback)";
                            } else {
                                omMessage = "ระบบ OM ตอบกลับข้อมูลรูปแบบไม่ถูกต้อง: " + entityStr;
                            }
                        }
                    } else {
                        omMessage = "ไม่สามารถเชื่อมต่อระบบ OM ได้ (HTTP " + response.getStatus() + ")";
                    }
                } finally {
                    client.close();
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to call OM Return In API for: " + trimmedMsisdn, e);
                omMessage = "เกิดข้อผิดพลาดในการเชื่อมต่อระบบ OM: " + e.getMessage();
            }
        }

        if (!omSuccess) {
            LOGGER.log(Level.WARNING, "TERMINATE REQUEST - FAILED: MSISDN={0}, OM_Error={1}", new Object[]{trimmedMsisdn, omMessage});
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Json.createObjectBuilder()
                            .add("error", "ไม่สามารถส่งคำสั่ง Terminate ไปยังระบบ OM ได้: " + omMessage)
                            .build().toString())
                    .build();
        }

        // Try to update OM DB record directly if the INV_MASTER tables exist
        int rowsUpdated = 0;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE INV_MASTER_PROFILE SET STATUS = 15, UPDATE_DATE = SYSTIMESTAMP, UPDATE_BY = 'number-checker' " +
                     "WHERE MASTER_ID = (SELECT MASTER_ID FROM INV_MASTER WHERE EXTERNAL_ID = ? AND IS_ACTIVE = 'Y') AND IS_ACTIVE = 'Y'")) {
            stmt.setString(1, trimmedMsisdn);
            rowsUpdated = stmt.executeUpdate();
            LOGGER.log(Level.INFO, "Direct OM DB status updated to 15 for: {0}, rows={1}", new Object[]{trimmedMsisdn, rowsUpdated});
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Direct OM DB update failed (skipping since INV_MASTER_PROFILE tables may not exist): " + e.getMessage());
        }

        // Delete from local NV_LOCAL_NUMBER_REGISTRY
        int rowsDeleted = 0;
        try (Connection conn = getConnection();
             PreparedStatement stmtLocal = conn.prepareStatement("DELETE FROM NV_LOCAL_NUMBER_REGISTRY WHERE MSISDN = ?")) {
            stmtLocal.setString(1, trimmedMsisdn);
            rowsDeleted = stmtLocal.executeUpdate();
            LOGGER.log(Level.INFO, "Deleted from local NV_LOCAL_NUMBER_REGISTRY for: {0}, rows={1}", new Object[]{trimmedMsisdn, rowsDeleted});
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to delete from NV_LOCAL_NUMBER_REGISTRY for: " + trimmedMsisdn, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Json.createObjectBuilder()
                            .add("error", "ระบบ OM สั่งทำรายการสำเร็จ แต่ไม่สามารถลบข้อมูลจากฐานข้อมูลท้องถิ่นได้: " + e.getMessage())
                            .build().toString())
                    .build();
        }

        String dbMsg = (rowsUpdated > 0)
                ? "อัปเดตสถานะเป็น Terminate (15) ในฐานข้อมูล OM สำเร็จ และลบข้อมูลท้องถิ่นสำเร็จ"
                : "ลบข้อมูลท้องถิ่นสำเร็จ (ระบบหลัก OM ไม่มีตาราง INV_MASTER_PROFILE หรือไม่พบเลขหมายนี้ในโปรไฟล์)";

        LOGGER.log(Level.INFO, "TERMINATE REQUEST - SUCCESS: MSISDN={0}, Mode={1}, OM_Message={2}, DB_Message={3}",
                new Object[]{trimmedMsisdn, force ? "Force" : "Normal", omMessage, dbMsg});

        invalidateCache();
        return Response.ok(Json.createObjectBuilder()
                .add("success", true)
                .add("message", "ยกเลิกบริการสำเร็จ: " + omMessage + " และ " + dbMsg)
                .build().toString())
                .build();
    }

    private static class ExcelRecord {
        String msisdn;
        String iccid;
        String imsi;
        String serviceType;
        String owner;
        String status;
        String remarks;

        ExcelRecord(String msisdn, String iccid, String imsi, String serviceType, String owner, String status, String remarks) {
            this.msisdn = msisdn;
            this.iccid = iccid;
            this.imsi = imsi;
            this.serviceType = serviceType;
            this.owner = owner;
            this.status = status;
            this.remarks = remarks;
        }
    }
}

