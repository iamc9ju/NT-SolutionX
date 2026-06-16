package com.project.checker.service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.client.Entity;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonArray;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ExternalSystemClientImpl implements ExternalSystemClient {

    private static final Logger LOGGER = Logger.getLogger(ExternalSystemClientImpl.class.getName());

    // Inject AppConfig to guarantee it runs @PostConstruct (and loads config.properties)
    // before this bean reads System properties below.
    @javax.inject.Inject
    private AppConfig appConfig;

    private Client client;
    private final Map<String, String> systemUrls = new HashMap<>();

    // Custom thread-safe database connection pool for Billing DB
    private final java.util.concurrent.BlockingQueue<java.sql.Connection> dbConnectionPool = 
            new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger currentDbConnections = 
            new java.util.concurrent.atomic.AtomicInteger(0);
    private boolean jndiAvailable = true;

    @PostConstruct
    public void init() {
        // Initialize JAX-RS Client with timeouts (Production Grade requirement)
        int connectTimeout = 10;
        int readTimeout = 30;
        try {
            connectTimeout = Integer.parseInt(System.getProperty("checker.api.timeout.connect", "10"));
        } catch (NumberFormatException e) {
            // ignore
        }
        try {
            readTimeout = Integer.parseInt(System.getProperty("checker.api.timeout.read", "30"));
        } catch (NumberFormatException e) {
            // ignore
        }

        this.client = ClientBuilder.newBuilder()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .build();

        // Load URLs from properties or env, fallback to default production URLs or simulation indicators
        configureSystemUrl("ocs_ocs", "checker.api.url.ocs_ocs", "OCS_OCS_API_URL", "MOCK");
        configureSystemUrl("ocs_iot", "checker.api.url.ocs_iot", "OCS_IOT_API_URL", "MOCK");
        configureSystemUrl("wom", "checker.api.url.wom", "WOM_API_URL", "http://10.36.1.48:8080/cat/ocsinterface/QueryCustomerInfo");
        configureSystemUrl("wom_iot", "checker.api.url.wom_iot", "WOM_IOT_API_URL", "http://10.36.1.47:8080/IOTConverter/queryCustomerInfo");
        configureSystemUrl("billing", "checker.api.url.billing", "BILLING_API_URL", "DB");
        configureSystemUrl("crm", "checker.api.url.crm", "CRM_API_URL", "http://10.36.1.48:8080/cat/crminterface/SERVICE-SEARCH-FROM-CA");
        configureSystemUrl("brm", "checker.api.url.brm", "BRM_API_URL", "http://10.36.1.47:8080/nonorderbrm/api/queryNonOrderBillingBrmService");
        configureSystemUrl("inventory", "checker.api.url.inventory", "INVENTORY_API_URL", "http://10.36.1.48:8080/cat/inventoryinterface/select-inven");
    }

    private void configureSystemUrl(String systemCode, String propertyKey, String envKey, String fallbackDefault) {
        String url = System.getProperty(propertyKey);
        if (url == null || url.trim().isEmpty()) {
            url = System.getenv(envKey);
        }
        if (url == null || url.trim().isEmpty()) {
            url = fallbackDefault;
        }
        systemUrls.put(systemCode, url);
        LOGGER.log(Level.INFO, "System {0} configured with URL: {1}", new Object[]{systemCode, url});
    }

    @Override
    public String checkPhoneNumber(String systemCode, String phoneNumber) throws Exception {
        String url = systemUrls.get(systemCode);
        if (url == null || "MOCK".equalsIgnoreCase(url)) {
            throw new RuntimeException("System URL is not configured or configured as MOCK: " + systemCode);
        }

        boolean isAvailableType = "ocs_ocs".equals(systemCode) || "ocs_iot".equals(systemCode) || "wom".equals(systemCode) || "wom_iot".equals(systemCode) || "brm".equals(systemCode) || "crm".equals(systemCode) || "inventory".equals(systemCode) || "billing".equals(systemCode);

        int maxRetries = 3;
        try {
            maxRetries = Integer.parseInt(System.getProperty("checker.api.max.retries", "3"));
        } catch (NumberFormatException e) {
            // ignore
        }
        
        long baseDelay = 1000;
        try {
            baseDelay = Long.parseLong(System.getProperty("checker.api.retry.delay.ms", "1000"));
        } catch (NumberFormatException e) {
            // ignore
        }

        int attempt = 0;

        while (true) {
            try {
                // Check if it is an OCS / WOM system request requiring specific POST QueryCustomerInfo schema
                if ("ocs_ocs".equals(systemCode) || "ocs_iot".equals(systemCode) || "wom".equals(systemCode) || "wom_iot".equals(systemCode)) {
                    // Construct QueryCustomerInfo JSON payload (Production Grade)
                    String dateStr = String.valueOf(System.currentTimeMillis());
                    JsonObject requestBody = Json.createObjectBuilder()
                            .add("requestHeader", Json.createObjectBuilder()
                                    .add("messageSeq", dateStr)
                                    .add("version", "1")
                                    .add("ownershipInfo", Json.createObjectBuilder().add("beid", 20101))
                                    .add("operatorInfo", Json.createObjectBuilder().add("operatorID", "101").add("channelID", "1"))
                                    .add("accessSecurity", Json.createObjectBuilder()
                                            .add("remoteIP", "10.36.1.48")
                                            .add("loginSystemCode", "OMTest1")
                                            .add("password", "uD8G1eRDTnGLNUMHbVQWXxLdr1WRa8MV9WNbd9CifK4=")))
                            .add("queryCustomerInfoRequest", Json.createObjectBuilder()
                                    .add("customerMask", "100")
                                    .add("accountMask", "10")
                                    .add("subscriberMask", "1111111")
                                    .add("queryMode", "0")
                                    .add("queryObj", Json.createObjectBuilder()
                                            .add("subAccessCode", Json.createObjectBuilder()
                                                    .add("primaryIdentity", phoneNumber))))
                            .build();

                    try (Response response = client.target(url)
                            .request(MediaType.APPLICATION_JSON)
                            .post(Entity.json(requestBody))) {

                        JsonObject responseJson = null;
                        if (response.hasEntity()) {
                            try {
                                String responseStr = response.readEntity(String.class);
                                try (javax.json.JsonReader jsonReader = Json.createReader(new java.io.StringReader(responseStr))) {
                                    responseJson = jsonReader.readObject();
                                }
                            } catch (Exception e) {
                                // ignore and handle by status code below if json parsing failed
                            }
                        }

                        if (responseJson != null && responseJson.containsKey("resultHeader")) {
                            JsonObject resultHeader = responseJson.getJsonObject("resultHeader");
                            String resultCode = "";
                            if (resultHeader.containsKey("resultCode")) {
                                javax.json.JsonValue val = resultHeader.get("resultCode");
                                if (val.getValueType() == javax.json.JsonValue.ValueType.NUMBER) {
                                    resultCode = String.valueOf(((javax.json.JsonNumber) val).longValue());
                                } else if (val.getValueType() == javax.json.JsonValue.ValueType.STRING) {
                                    resultCode = ((javax.json.JsonString) val).getString();
                                } else {
                                    resultCode = val.toString().replaceAll("\"", "");
                                }
                            }
                            
                            if ("wom".equals(systemCode) || "wom_iot".equals(systemCode)) {
                                if ("20000003".equals(resultCode)) {
                                    return "Available";
                                } else {
                                    return "Active";
                                }
                            }

                            if ("0".equals(resultCode)) {
                                if (responseJson.containsKey("queryCustomerInfoResult")) {
                                    JsonObject result = responseJson.getJsonObject("queryCustomerInfoResult");
                                    if (result.containsKey("subscriber")) {
                                        JsonArray subscriberArr = result.getJsonArray("subscriber");
                                        if (subscriberArr != null && !subscriberArr.isEmpty()) {
                                            JsonObject subscriberObj = subscriberArr.getJsonObject(0);
                                            if (subscriberObj.containsKey("subscriberInfo")) {
                                                JsonObject subInfo = subscriberObj.getJsonObject("subscriberInfo");
                                                String status = subInfo.getString("status", "");
                                                if ("2".equals(status)) {
                                                    return "Active";
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            return "Inactive";
                        }

                        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                            throw new RuntimeException("HTTP Error " + response.getStatus() + ": " + response.getStatusInfo());
                        }
                        return "Inactive";
                    }
                } else if ("brm".equals(systemCode)) {
                    // Construct BRM JSON payload (Production Grade)
                    JsonObject requestBody = Json.createObjectBuilder()
                            .add("taskSEQ", "4")
                            .add("saleEmpId", "00000003")
                            .add("msisdn", phoneNumber)
                            .build();

                    try (Response response = client.target(url)
                            .request(MediaType.APPLICATION_JSON)
                            .post(Entity.json(requestBody))) {

                        JsonObject responseJson = null;
                        if (response.hasEntity()) {
                            try {
                                String responseStr = response.readEntity(String.class);
                                try (javax.json.JsonReader jsonReader = Json.createReader(new java.io.StringReader(responseStr))) {
                                    responseJson = jsonReader.readObject();
                                }
                            } catch (Exception e) {
                                // ignore and handle by status code below if json parsing failed
                            }
                        }

                        if (responseJson != null) {
                            String statusVal = "";
                            if (responseJson.containsKey("status")) {
                                javax.json.JsonValue val = responseJson.get("status");
                                if (val.getValueType() == javax.json.JsonValue.ValueType.STRING) {
                                    statusVal = ((javax.json.JsonString) val).getString();
                                } else {
                                    statusVal = val.toString().replaceAll("\"", "");
                                }
                            }

                            long codeVal = -1;
                            if (responseJson.containsKey("code")) {
                                javax.json.JsonValue val = responseJson.get("code");
                                if (val.getValueType() == javax.json.JsonValue.ValueType.NUMBER) {
                                    codeVal = ((javax.json.JsonNumber) val).longValue();
                                } else if (val.getValueType() == javax.json.JsonValue.ValueType.STRING) {
                                    try {
                                        codeVal = Long.parseLong(((javax.json.JsonString) val).getString());
                                    } catch (Exception e) {}
                                } else {
                                    try {
                                        codeVal = Long.parseLong(val.toString().replaceAll("\"", ""));
                                    } catch (Exception e) {}
                                }
                            }

                            if ("success".equalsIgnoreCase(statusVal) || codeVal == 200) {
                                return "Active";
                            } else if ("fail".equalsIgnoreCase(statusVal) || codeVal == 400) {
                                return "Available";
                            }
                        }

                        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                            throw new RuntimeException("HTTP Error " + response.getStatus() + ": " + response.getStatusInfo());
                        }
                        return "Available";
                    }
                } else if ("billing".equals(systemCode)) {
                    String dbUrl = System.getProperty("billing.datasource.url");
                    if (dbUrl == null || dbUrl.trim().isEmpty()) {
                        dbUrl = System.getenv("BILLING_DATASOURCE_URL");
                    }
                    if (dbUrl == null || dbUrl.trim().isEmpty()) {
                        dbUrl = "jdbc:oracle:thin:@10.32.17.88:1521:catpcu1";
                    }

                    String dbUser = System.getProperty("billing.datasource.username");
                    if (dbUser == null || dbUser.trim().isEmpty()) {
                        dbUser = System.getenv("BILLING_DATASOURCE_USERNAME");
                    }
                    if (dbUser == null || dbUser.trim().isEmpty()) {
                        dbUser = "arbor";
                    }

                    String dbPass = System.getProperty("billing.datasource.password");
                    if (dbPass == null || dbPass.trim().isEmpty()) {
                        dbPass = System.getenv("BILLING_DATASOURCE_PASSWORD");
                    }
                    if (dbPass == null || dbPass.trim().isEmpty()) {
                        dbPass = "arbor123";
                    }

                    java.sql.Connection conn = null;
                    try {
                        conn = getDbConnection(dbUrl, dbUser, dbPass);
                        try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                                 "select 1 from CUSTOMER_ID_EQUIP_MAP " +
                                 "where EXTERNAL_ID = ? and EXTERNAL_ID_TYPE = 17 and inactive_date is null and rownum = 1")) {
                            stmt.setString(1, phoneNumber);
                            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) {
                                    return "Active";
                                } else {
                                    return "Available";
                                }
                            }
                        }
                    } finally {
                        releaseDbConnection(conn);
                    }
                } else if ("crm".equals(systemCode)) {
                    // Construct CRM JSON payload (Production Grade)
                    JsonObject requestBody = Json.createObjectBuilder()
                            .add("request_message", Json.createObjectBuilder()
                                    .add("property_one", phoneNumber))
                            .build();

                    // Format current time as ISO offset date time string (e.g. 2026-06-10T15:24:59.510+07:00)
                    String currentIsoDate = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                            .format(java.time.OffsetDateTime.now());

                    try (Response response = client.target(url)
                            .request(MediaType.APPLICATION_JSON)
                            .header("interface_type", "SERVICE")
                            .header("interface_code", "SERVICE-SEARCH-FROM-CA")
                            .header("interface_source", "MY-FRONT-END")
                            .header("interface_id", "12MAR20-" + String.valueOf(System.currentTimeMillis() % 100000000L))
                            .header("request_date", currentIsoDate)
                            .header("request_by", "USSD")
                            .post(Entity.json(requestBody))) {

                        JsonObject responseJson = null;
                        if (response.hasEntity()) {
                            try {
                                String responseStr = response.readEntity(String.class);
                                try (javax.json.JsonReader jsonReader = Json.createReader(new java.io.StringReader(responseStr))) {
                                    responseJson = jsonReader.readObject();
                                }
                            } catch (Exception e) {
                                // ignore and handle by status code below if json parsing failed
                            }
                        }

                        if (responseJson != null) {
                            String resCode = "";
                            if (responseJson.containsKey("response_code")) {
                                javax.json.JsonValue val = responseJson.get("response_code");
                                if (val.getValueType() == javax.json.JsonValue.ValueType.STRING) {
                                    resCode = ((javax.json.JsonString) val).getString();
                                } else {
                                    resCode = val.toString().replaceAll("\"", "");
                                }
                            }

                            long totalService = 0;
                            if (responseJson.containsKey("response_items")) {
                                javax.json.JsonValue itemsVal = responseJson.get("response_items");
                                if (itemsVal.getValueType() == javax.json.JsonValue.ValueType.OBJECT) {
                                    JsonObject itemsObj = (JsonObject) itemsVal;
                                    if (itemsObj.containsKey("total_service")) {
                                        javax.json.JsonValue val = itemsObj.get("total_service");
                                        if (val.getValueType() == javax.json.JsonValue.ValueType.NUMBER) {
                                            totalService = ((javax.json.JsonNumber) val).longValue();
                                        } else if (val.getValueType() == javax.json.JsonValue.ValueType.STRING) {
                                            try {
                                                totalService = Long.parseLong(((javax.json.JsonString) val).getString());
                                            } catch (Exception e) {}
                                        } else {
                                            try {
                                                totalService = Long.parseLong(val.toString().replaceAll("\"", ""));
                                            } catch (Exception e) {}
                                        }
                                    }
                                }
                            }

                            if ("SUCCESS".equalsIgnoreCase(resCode)) {
                                if (totalService > 0) {
                                    return "Active";
                                } else {
                                    return "Available";
                                }
                            }
                        }

                        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                            throw new RuntimeException("HTTP Error " + response.getStatus() + ": " + response.getStatusInfo());
                        }
                        return "Available";
                    }
                } else if ("inventory".equals(systemCode)) {
                    // Construct Inventory JSON payload (Production Grade JSON Array)
                    javax.json.JsonArray requestBody = Json.createArrayBuilder()
                            .add(Json.createObjectBuilder().add("msisdn", phoneNumber))
                            .build();

                    try (Response response = client.target(url)
                            .request(MediaType.APPLICATION_JSON)
                            .post(Entity.json(requestBody))) {

                        JsonObject responseJson = null;
                        if (response.hasEntity()) {
                            try {
                                String responseStr = response.readEntity(String.class);
                                try (javax.json.JsonReader jsonReader = Json.createReader(new java.io.StringReader(responseStr))) {
                                    responseJson = jsonReader.readObject();
                                }
                            } catch (Exception e) {
                                // ignore and handle by status code below if json parsing failed
                            }
                        }

                        if (responseJson != null && responseJson.containsKey("msisdns")) {
                            javax.json.JsonValue msisdnsVal = responseJson.get("msisdns");
                            if (msisdnsVal.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
                                JsonArray array = (JsonArray) msisdnsVal;
                                if (!array.isEmpty()) {
                                    return "Active";
                                } else {
                                    return "Available";
                                }
                            }
                        }

                        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                            throw new RuntimeException("HTTP Error " + response.getStatus() + ": " + response.getStatusInfo());
                        }
                        return "Available";
                    }
                } else {
                    // Fallback standard GET request for other systems
                    WebTarget target = client.target(url).queryParam("phone", phoneNumber);
                    try (Response response = target.request(MediaType.APPLICATION_JSON).get()) {

                        if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                            String responseBody = response.readEntity(String.class);
                            if (responseBody != null) {
                                String cleanBody = responseBody.trim().toUpperCase();
                                if (cleanBody.contains("ACTIVE") || cleanBody.contains("AVAILABLE") || cleanBody.contains("USED")) {
                                    return "Active";
                                }
                            }
                            return isAvailableType ? "Available" : "Inactive";
                        } else {
                            throw new RuntimeException("HTTP Error " + response.getStatus() + ": " + response.getStatusInfo());
                        }
                    }
                }
            } catch (Exception e) {
                attempt++;
                if (attempt > maxRetries) {
                    LOGGER.log(Level.SEVERE, "Error calling API for system " + systemCode + " phone " + phoneNumber + " after " + attempt + " attempts", e);
                    throw e;
                }
                
                // Exponential backoff: baseDelay * 2^(attempt-1)
                long backoffFactor = (long) Math.pow(2, attempt - 1);
                long delay = baseDelay * backoffFactor;
                // Add randomized jitter up to 50% of the delay
                long jitter = (long) (Math.random() * (delay / 2));
                long totalDelay = delay + jitter;

                LOGGER.log(Level.WARNING, "System {0} failed on attempt {1} for phone {2} due to {3}. Retrying in {4}ms...",
                        new Object[]{systemCode, attempt, phoneNumber, e.getMessage() != null ? e.getMessage() : e.toString(), totalDelay});
                try {
                    Thread.sleep(totalDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private String simulateApiCall(String systemCode, String phoneNumber) throws Exception {
        // Simulate network latency (100ms)
        Thread.sleep(100);

        // Simulate random failure (5% chance)
        if (Math.random() < 0.05) {
            throw new RuntimeException("Connection timeout to " + systemCode + " API");
        }

        // Try to check database status to make simulation aligned with database registry
        String dbStatus = null;
        String dbUrl = System.getProperty("master.datasource.url", "jdbc:oracle:thin:@10.36.1.51:1521:OMDB");
        String dbUser = System.getProperty("master.datasource.username", "omuser");
        String dbPass = System.getProperty("master.datasource.password", "xjfeil92");
        try {
            Class.forName("oracle.jdbc.OracleDriver");
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl, dbUser, dbPass);
                 java.sql.PreparedStatement stmt = conn.prepareStatement("SELECT STATUS FROM NV_NUMBER_REGISTRY WHERE MSISDN = ?")) {
                stmt.setString(1, phoneNumber);
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        dbStatus = rs.getString("STATUS");
                    }
                }
            }
        } catch (Exception e) {
            // Ignore DB query errors for simulation
        }

        boolean isAvailableType = "ocs_ocs".equals(systemCode) || "ocs_iot".equals(systemCode) || "wom".equals(systemCode) || "wom_iot".equals(systemCode) || "brm".equals(systemCode) || "crm".equals(systemCode) || "inventory".equals(systemCode) || "billing".equals(systemCode);

        if (dbStatus != null) {
            if ("AVAILABLE".equalsIgnoreCase(dbStatus)) {
                return isAvailableType ? "Available" : "Inactive";
            } else if ("ACTIVE".equalsIgnoreCase(dbStatus)) {
                return isAvailableType ? "Active" : "Active";
            }
        }

        // Fallback simulation if not in DB
        try {
            long num = Long.parseLong(phoneNumber.replaceAll("[^0-9]", ""));
            if (isAvailableType) {
                return num % 2 == 0 ? "Available" : "Active";
            } else {
                return num % 2 == 0 ? "Active" : "Inactive";
            }
        } catch (NumberFormatException e) {
            if (isAvailableType) {
                return "Available";
            } else {
                return "Active";
            }
        }
    }

    @Override
    public boolean isMock(String systemCode) {
        String url = systemUrls.get(systemCode);
        return url == null || "MOCK".equalsIgnoreCase(url);
    }
    private java.sql.Connection createConnectionWithTimeout(String dbUrl, String dbUser, String dbPass) throws Exception {
        Class.forName("oracle.jdbc.OracleDriver");
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", dbUser);
        props.setProperty("password", dbPass);
        props.setProperty("oracle.net.CONNECT_TIMEOUT", "3000"); // 3 seconds connect timeout
        props.setProperty("oracle.jdbc.ReadTimeout", "3000"); // 3 seconds read timeout
        return java.sql.DriverManager.getConnection(dbUrl, props);
    }

    private java.sql.Connection getDbConnection(String dbUrl, String dbUser, String dbPass) throws Exception {
        if (jndiAvailable) {
            String jndiName = System.getProperty("billing.datasource.jndi");
            if (jndiName == null || jndiName.trim().isEmpty()) {
                jndiName = System.getenv("BILLING_DATASOURCE_JNDI");
            }
            if (jndiName != null && !jndiName.trim().isEmpty()) {
                try {
                    javax.naming.InitialContext ctx = new javax.naming.InitialContext();
                    javax.sql.DataSource ds = (javax.sql.DataSource) ctx.lookup(jndiName.trim());
                    return ds.getConnection();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to lookup JNDI DataSource: " + jndiName + ", falling back to direct JDBC connection", e);
                    jndiAvailable = false;
                }
            } else {
                jndiAvailable = false;
            }
        }

        java.sql.Connection conn = dbConnectionPool.poll();
        if (conn != null) {
            try {
                if (conn.isValid(2)) {
                    return conn;
                } else {
                    try { conn.close(); } catch (Exception e) {}
                    currentDbConnections.decrementAndGet();
                }
            } catch (Exception e) {
                try { conn.close(); } catch (Exception ex) {}
                currentDbConnections.decrementAndGet();
            }
        }
        
        int maxConns = 4;
        try {
            maxConns = Integer.parseInt(System.getProperty("checker.concurrency.limit", "3")) + 1;
        } catch (NumberFormatException e) {}

        if (currentDbConnections.get() < maxConns) {
            synchronized (this) {
                if (currentDbConnections.get() < maxConns) {
                    try {
                        java.sql.Connection newConn = createConnectionWithTimeout(dbUrl, dbUser, dbPass);
                        currentDbConnections.incrementAndGet();
                        return newConn;
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Failed to create new Oracle connection", e);
                        throw e;
                    }
                }
            }
        }
        
        conn = dbConnectionPool.poll(10, TimeUnit.SECONDS);
        if (conn == null) {
            return createConnectionWithTimeout(dbUrl, dbUser, dbPass);
        }
        
        try {
            if (conn.isValid(2)) {
                return conn;
            } else {
                try { conn.close(); } catch (Exception e) {}
                currentDbConnections.decrementAndGet();
                java.sql.Connection newConn = createConnectionWithTimeout(dbUrl, dbUser, dbPass);
                currentDbConnections.incrementAndGet();
                return newConn;
            }
        } catch (Exception e) {
            try { conn.close(); } catch (Exception ex) {}
            currentDbConnections.decrementAndGet();
            java.sql.Connection newConn = createConnectionWithTimeout(dbUrl, dbUser, dbPass);
            currentDbConnections.incrementAndGet();
            return newConn;
        }
    }

    private void releaseDbConnection(java.sql.Connection conn) {
        if (conn == null) return;
        
        if (jndiAvailable) {
            try {
                conn.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error closing JNDI connection", e);
            }
            return;
        }

        try {
            int maxConns = 4;
            try {
                maxConns = Integer.parseInt(System.getProperty("checker.concurrency.limit", "3")) + 1;
            } catch (NumberFormatException e) {}

            if (dbConnectionPool.size() < maxConns && !conn.isClosed()) {
                dbConnectionPool.offer(conn);
            } else {
                try { conn.close(); } catch (Exception e) {}
                currentDbConnections.decrementAndGet();
            }
        } catch (Exception e) {
            try { conn.close(); } catch (Exception ex) {}
            currentDbConnections.decrementAndGet();
        }
    }

    @PreDestroy
    public void cleanup() {
        if (client != null) {
            client.close();
            LOGGER.info("JAX-RS Client closed.");
        }
        
        java.sql.Connection conn;
        while ((conn = dbConnectionPool.poll()) != null) {
            try {
                conn.close();
            } catch (Exception e) {
                // ignore
            }
        }
        LOGGER.info("Oracle DB Connection Pool cleaned up.");
    }
}
