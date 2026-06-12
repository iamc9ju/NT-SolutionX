package com.project.checker.service;

import com.project.checker.model.CheckResultRow;
import com.project.checker.model.JobMetadata;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTable;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableStyleInfo;

import javax.annotation.Resource;
import javax.ejb.Asynchronous;
import javax.ejb.Stateless;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.inject.Inject;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

@Stateless
public class PhoneCheckProcessor {

    private static final Logger LOGGER = Logger.getLogger(PhoneCheckProcessor.class.getName());

    @Inject
    private JobManager jobManager;

    @Inject
    private ExternalSystemClient systemClient;

    @Resource(lookup = "java:comp/DefaultManagedExecutorService")
    private ManagedExecutorService executor;

    @Asynchronous
    public void startProcessing(String[] targetSystems) {
        JobMetadata metadata = jobManager.getMetadata();
        if (metadata == null) {
            LOGGER.severe("No active job metadata found when starting check processor.");
            return;
        }

        // Lock in THIS job's unique ID so we can detect if a newer job supersedes us
        final String myJobId = metadata.getJobId();

        // Filter out systems that are not configured (i.e. are using MOCK)
        List<String> activeSystemsList = new ArrayList<>();
        for (String sys : targetSystems) {
            if (!systemClient.isMock(sys)) {
                activeSystemsList.add(sys);
            }
        }

        // Fallback: If no system is configured with a real API, keep all targetSystems
        // so that local mock/simulation testing remains functional.
        final String[] systemsToProcess = !activeSystemsList.isEmpty()
                ? activeSystemsList.toArray(new String[0])
                : targetSystems;

        LOGGER.log(Level.INFO, "Starting phone checker job {0} for systems {1}",
                new Object[]{myJobId, Arrays.toString(systemsToProcess)});

        try {
            // 1. Read phone numbers supporting XLSX, XLS, CSV, TXT
            List<String> phoneNumbers = readPhoneNumbers(jobManager.getInputExcelFile(), metadata.getFileName(), metadata);
            
            if (phoneNumbers.isEmpty()) {
                throw new IllegalArgumentException("No phone numbers found in the uploaded file.");
            }

            metadata.setTotalNumbers(phoneNumbers.size());
            if (!saveIfStillOwner(myJobId, metadata)) return;

            List<CheckResultRow> results = new ArrayList<>();
            for (String phone : phoneNumbers) {
                CheckResultRow row = new CheckResultRow(phone);
                for (String sys : systemsToProcess) {
                    row.getSystems().put(sys, "WAITING");
                }
                results.add(row);
            }

            // Save initial preview with WAITING status for all systems to initialize table structure on the frontend immediately
            synchronized (metadata) {
                metadata.setProcessedNumbers(0);
                metadata.setSuccessCount(0);
                metadata.setFailedCount(0);
                metadata.setResultsPreview(new ArrayList<>(results));
                if (!saveIfStillOwner(myJobId, metadata)) return;
            }

            final AtomicInteger processedCount = new AtomicInteger(0);
            final AtomicInteger successCount = new AtomicInteger(0);
            final AtomicInteger failedCount = new AtomicInteger(0);
            final AtomicLong lastSaveTime = new AtomicLong(System.currentTimeMillis());

            int concurrencyLimit = 3;
            try {
                concurrencyLimit = Integer.parseInt(System.getProperty("checker.concurrency.limit", "3"));
            } catch (NumberFormatException e) {
                // ignore
            }
            final Semaphore semaphore = new Semaphore(concurrencyLimit);
            List<CompletableFuture<Void>> rowFutures = new ArrayList<>();

            for (CheckResultRow row : results) {
                String phone = row.getPhoneNumber();
                
                CompletableFuture<Void> rowFuture = CompletableFuture.runAsync(() -> {
                    if (!isStillOwner(myJobId)) {
                        return;
                    }
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    
                    try {
                        // Process each system sequentially for this phone number to limit concurrent requests
                        for (String sysCode : systemsToProcess) {
                            if (!isStillOwner(myJobId)) {
                                return;
                            }
                            try {
                                String apiResult = systemClient.checkPhoneNumber(sysCode, phone);
                                row.getSystems().put(sysCode, apiResult);
                                row.getRawResponses().put(sysCode, "Success");
                            } catch (Exception e) {
                                row.getSystems().put(sysCode, "ERROR");
                                row.getRawResponses().put(sysCode, e.getMessage() != null ? e.getMessage() : e.toString());
                            }
                        }

                        int currentProcessed = processedCount.incrementAndGet();

                        // Check if the overall row check succeeded or had errors
                        boolean rowSuccess = true;
                        for (String sysCode : systemsToProcess) {
                            if ("ERROR".equals(row.getSystems().get(sysCode))) {
                                rowSuccess = false;
                                break;
                            }
                        }

                        if (rowSuccess) {
                            successCount.incrementAndGet();
                        } else {
                            failedCount.incrementAndGet();
                        }

                        // Save progress on disk every 20 records (or if 1 second has elapsed since last save)
                        long now = System.currentTimeMillis();
                        long lastSave = lastSaveTime.get();
                        if (currentProcessed % 20 == 0 || currentProcessed == phoneNumbers.size() || (now - lastSave > 1000)) {
                            if (lastSaveTime.compareAndSet(lastSave, now)) {
                                synchronized (metadata) {
                                    metadata.setProcessedNumbers(currentProcessed);
                                    metadata.setSuccessCount(successCount.get());
                                    metadata.setFailedCount(failedCount.get());

                                    // Send all results back to Vue frontend
                                    metadata.setResultsPreview(new ArrayList<>(results));

                                    saveIfStillOwner(myJobId, metadata);
                                }
                            }
                        }
                    } finally {
                        semaphore.release();
                    }
                }, executor);

                rowFutures.add(rowFuture);
            }

            // Wait for all row futures to complete
            CompletableFuture.allOf(rowFutures.toArray(new CompletableFuture[0])).join();

            // Local class to track failed checks during retry
            class FailedCheck {
                final CheckResultRow row;
                final String sysCode;
                FailedCheck(CheckResultRow row, String sysCode) {
                    this.row = row;
                    this.sysCode = sysCode;
                }
            }

            // Application-level retry phase: If there are failed system checks, retry them up to 5 times
            int maxJobRetries = 5;
            for (int attempt = 1; attempt <= maxJobRetries; attempt++) {
                if (!isStillOwner(myJobId)) {
                    break;
                }

                List<FailedCheck> currentFailures = new ArrayList<>();
                for (CheckResultRow row : results) {
                    for (String sysCode : systemsToProcess) {
                        if ("ERROR".equals(row.getSystems().get(sysCode))) {
                            currentFailures.add(new FailedCheck(row, sysCode));
                        }
                    }
                }

                if (currentFailures.isEmpty()) {
                    break; // All checks succeeded!
                }

                LOGGER.log(Level.INFO, "Job {0} retry attempt {1}/5 for {2} failed system checks...", 
                        new Object[]{myJobId, attempt, currentFailures.size()});

                // Process failed checks in parallel using a low concurrency (Semaphore = 2) to be gentle on legacy systems
                final Semaphore retrySemaphore = new Semaphore(2);
                List<CompletableFuture<Void>> retryFutures = new ArrayList<>();

                for (FailedCheck fail : currentFailures) {
                    CompletableFuture<Void> rf = CompletableFuture.runAsync(() -> {
                        if (!isStillOwner(myJobId)) {
                            return;
                        }
                        try {
                            retrySemaphore.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            // Delay slightly before hitting the server (be gentle)
                            Thread.sleep(500);

                            String phone = fail.row.getPhoneNumber();
                            String sysCode = fail.sysCode;

                            String apiResult = systemClient.checkPhoneNumber(sysCode, phone);
                            fail.row.getSystems().put(sysCode, apiResult);
                            fail.row.getRawResponses().put(sysCode, "Success");
                        } catch (Exception e) {
                            fail.row.getSystems().put(fail.sysCode, "ERROR");
                            fail.row.getRawResponses().put(fail.sysCode, e.getMessage() != null ? e.getMessage() : e.toString());
                        } finally {
                            retrySemaphore.release();
                        }
                    }, executor);
                    retryFutures.add(rf);
                }

                CompletableFuture.allOf(retryFutures.toArray(new CompletableFuture[0])).join();
            }

            // Set final counts for writing to Excel and final metadata
            int finalProcessed = results.size();
            int finalSuccess = 0;
            int finalFailed = 0;
            for (CheckResultRow row : results) {
                boolean rowSuccess = true;
                for (String sysCode : systemsToProcess) {
                    if ("ERROR".equals(row.getSystems().get(sysCode))) {
                        rowSuccess = false;
                        break;
                    }
                }
                if (rowSuccess) {
                    finalSuccess++;
                } else {
                    finalFailed++;
                }
            }

            metadata.setProcessedNumbers(finalProcessed);
            metadata.setSuccessCount(finalSuccess);
            metadata.setFailedCount(finalFailed);
            // Send all results back to Vue frontend
            metadata.setResultsPreview(new ArrayList<>(results));

            // 4. Write full result outputs to results.xlsx using SXSSF (Streaming POI for memory efficiency)
            if (!isStillOwner(myJobId)) {
                LOGGER.log(Level.WARNING, "Job {0} was superseded before writing Excel. Aborting.", myJobId);
                return;
            }
            writeResultsToExcel(results, systemsToProcess);

            // 5. Transition to complete status (DONE)
            metadata.setStatus("DONE");
            metadata.setCompletedAt(LocalDateTime.now().toString());
            if (!saveIfStillOwner(myJobId, metadata)) return;
            LOGGER.log(Level.INFO, "Job {0} completed successfully. Processed: {1}",
                    new Object[]{myJobId, finalProcessed});

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed executing job " + myJobId, e);
            if (isStillOwner(myJobId)) {
                metadata.setStatus("FAILED");
                metadata.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.toString());
                jobManager.saveMetadata(metadata);
            }
        }
    }

    /** Returns true if this job (myJobId) is still the active job on disk. */
    private boolean isStillOwner(String myJobId) {
        JobMetadata current = jobManager.getMetadata();
        return current != null && myJobId.equals(current.getJobId());
    }

    /** Saves metadata only if still the active job. Returns false if superseded and save was skipped. */
    private boolean saveIfStillOwner(String myJobId, JobMetadata metadata) {
        if (!isStillOwner(myJobId)) {
            LOGGER.log(Level.WARNING, "Job {0} aborted: a newer job has started.", myJobId);
            return false;
        }
        jobManager.saveMetadata(metadata);
        return true;
    }



    private List<String> readPhoneNumbers(File file, String originalFileName, JobMetadata metadata) throws Exception {
        List<String> numbers = new ArrayList<>();
        String extension = getFileExtension(originalFileName);
        DataFormatter formatter = new DataFormatter();

        if ("xlsx".equalsIgnoreCase(extension) || "xls".equalsIgnoreCase(extension)) {
            try (InputStream fis = new FileInputStream(file);
                 Workbook workbook = WorkbookFactory.create(fis)) {
                Sheet sheet = workbook.getSheetAt(0);
                
                int targetColIdx = 0; // Default to column A (index 0)
                
                // 1. Scan row 0 to find "MSISDN" column header
                Row headerRow = sheet.getRow(0);
                if (headerRow != null) {
                    for (Cell cell : headerRow) {
                        if (cell != null) {
                            String headerVal = getCellStringValue(cell, formatter).trim();
                            if (headerVal.equalsIgnoreCase("MSISDN") || headerVal.contains("เบอร์")) {
                                targetColIdx = cell.getColumnIndex();
                                break;
                            }
                        }
                    }
                }
                
                // 2. Fallback check: if column 0 has no data, check column B (index 1)
                if (targetColIdx == 0) {
                    boolean colZeroHasData = false;
                    int maxRowsToCheck = Math.min(sheet.getLastRowNum() + 1, 10);
                    for (int i = 0; i < maxRowsToCheck; i++) {
                        Row r = sheet.getRow(i);
                        if (r != null) {
                            Cell c = r.getCell(0);
                            if (c != null && !getCellStringValue(c, formatter).trim().isEmpty()) {
                                colZeroHasData = true;
                                break;
                            }
                        }
                    }
                    if (!colZeroHasData) {
                        targetColIdx = 1; // Use column B (MSISDN column)
                    }
                }

                LOGGER.log(Level.INFO, "Excel reader detected MSISDN column index: {0}", targetColIdx);
                String initMsg = "[XLSX Reader] Starting to read phone numbers from column index: " + targetColIdx;
                System.out.println(initMsg);
                metadata.getImportLogs().add(initMsg);

                // 3. Read numbers from the detected column (skipping header value)
                for (int rIdx = 0; rIdx <= sheet.getLastRowNum(); rIdx++) {
                    Row row = sheet.getRow(rIdx);
                    if (row != null) {
                        Cell cell = row.getCell(targetColIdx);
                        if (cell != null) {
                            String rawVal = getCellStringValue(cell, formatter);
                            if (rawVal.trim().equalsIgnoreCase("MSISDN")) {
                                String skipMsg = "[XLSX Reader] Row " + rIdx + " - Skip header: \"" + rawVal + "\"";
                                System.out.println(skipMsg);
                                metadata.getImportLogs().add(skipMsg);
                                continue; // Skip header row
                            }
                            String val = cleanPhoneNumber(rawVal);
                            String logMsg = "[XLSX Reader] Row " + rIdx + " | Cell Type: " + cell.getCellType() + " | Raw Value: \"" + rawVal + "\" | Cleaned: \"" + val + "\"";
                            System.out.println(logMsg);
                            LOGGER.log(Level.INFO, logMsg);
                            metadata.getImportLogs().add(logMsg);
                            if (!val.isEmpty()) {
                                numbers.add(val);
                            }
                        } else {
                            String nullMsg = "[XLSX Reader] Row " + rIdx + " | Cell is null";
                            System.out.println(nullMsg);
                            metadata.getImportLogs().add(nullMsg);
                        }
                    }
                }
                String totalMsg = "[XLSX Reader] Total extracted numbers: " + numbers.size();
                System.out.println(totalMsg);
                metadata.getImportLogs().add(totalMsg);
            }
        } else {
            // Text / CSV reader fallback with dynamic column detection
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
                String line;
                int targetColIdx = 0;
                boolean isFirstLine = true;
                int lineCount = 0;
                
                String initMsg = "[CSV Reader] Starting to read phone numbers...";
                System.out.println(initMsg);
                metadata.getImportLogs().add(initMsg);

                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    String[] tokens = line.split("[,;]");
                    if (tokens.length > 0) {
                        if (isFirstLine) {
                            isFirstLine = false;
                            for (int i = 0; i < tokens.length; i++) {
                                String tok = tokens[i].trim();
                                if (tok.equalsIgnoreCase("MSISDN") || tok.contains("เบอร์")) {
                                    targetColIdx = i;
                                    break;
                                }
                            }
                            if (targetColIdx == 0 && tokens[0].trim().isEmpty() && tokens.length > 1) {
                                targetColIdx = 1;
                            }
                            String colMsg = "[CSV Reader] Detected target column index: " + targetColIdx;
                            System.out.println(colMsg);
                            metadata.getImportLogs().add(colMsg);

                            if (tokens[targetColIdx].trim().equalsIgnoreCase("MSISDN")) {
                                String skipMsg = "[CSV Reader] Line " + lineCount + " - Skip header: \"" + tokens[targetColIdx] + "\"";
                                System.out.println(skipMsg);
                                metadata.getImportLogs().add(skipMsg);
                                continue;
                            }
                        }
                        
                        if (targetColIdx < tokens.length) {
                            String rawVal = tokens[targetColIdx];
                            String val = cleanPhoneNumber(rawVal);
                            String logMsg = "[CSV Reader] Line " + lineCount + " | Raw Value: \"" + rawVal + "\" | Cleaned: \"" + val + "\"";
                            System.out.println(logMsg);
                            LOGGER.log(Level.INFO, logMsg);
                            metadata.getImportLogs().add(logMsg);
                            if (!val.isEmpty()) {
                                numbers.add(val);
                            }
                        }
                    }
                }
                String totalMsg = "[CSV Reader] Total extracted numbers: " + numbers.size();
                System.out.println(totalMsg);
                metadata.getImportLogs().add(totalMsg);
            }
        }
        return numbers;
    }

    private String cleanPhoneNumber(String phone) {
        if (phone == null) return "";
        // Clean Excel decimal floating numbers (e.g. "0812345678.0")
        if (phone.endsWith(".0")) {
            phone = phone.substring(0, phone.length() - 2);
        }
        // Retain only numeric digits
        return phone.replaceAll("[^0-9]", "").trim();
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(idx + 1) : "";
    }

    private void writeResultsToExcel(List<CheckResultRow> results, String[] systems) throws Exception {
        // Create in-memory XSSFWorkbook to support native Excel Tables
        XSSFWorkbook workbook = new XSSFWorkbook();
        try {
            XSSFSheet sheet = workbook.createSheet("Check Results");
            
            // Create native Excel Table
            XSSFTable table = sheet.createTable();
            table.setDisplayName("CheckResultsTable");
            table.setName("CheckResultsTable");
            
            // 1 column (MSISDN), plus systems columns, plus Error Details column.
            // Total columns = systems.length + 2.
            // Rightmost column index is systems.length + 1.
            AreaReference reference = workbook.getCreationHelper().createAreaReference(
                    new CellReference(0, 0), new CellReference(results.size(), systems.length + 1));
            table.setArea(reference);
            
            CTTable cttable = table.getCTTable();
            CTTableStyleInfo styleInfo = cttable.addNewTableStyleInfo();
            styleInfo.setName("TableStyleLight9"); // Clean alternating rows theme matching the UI!
            styleInfo.setShowColumnStripes(false);
            styleInfo.setShowRowStripes(true);

            // Default Color Map
            DefaultIndexedColorMap colorMap = new DefaultIndexedColorMap();

            // Create Colors
            XSSFColor headerBgColor = new XSSFColor(new java.awt.Color(10, 61, 107), colorMap); // #0A3D6B Deep Blue
            XSSFColor oddRowBgColor = new XSSFColor(new java.awt.Color(240, 246, 252), colorMap); // #F0F6FC Light Blue
            XSSFColor whiteColor = new XSSFColor(new java.awt.Color(255, 255, 255), colorMap);
            XSSFColor textDarkColor = new XSSFColor(new java.awt.Color(51, 51, 51), colorMap); // #333333
            XSSFColor textGrayColor = new XSSFColor(new java.awt.Color(108, 117, 125), colorMap); // #6C757D
            
            XSSFColor greenTextColor = new XSSFColor(new java.awt.Color(15, 81, 50), colorMap); // #0F5132
            XSSFColor greenBgColor = new XSSFColor(new java.awt.Color(209, 231, 221), colorMap); // #D1E7DD
            
            XSSFColor activeTextColor = new XSSFColor(new java.awt.Color(10, 61, 107), colorMap); // #0A3D6B
            XSSFColor activeBgColor = new XSSFColor(new java.awt.Color(214, 233, 248), colorMap); // #D6E9F8
            
            XSSFColor errorTextColor = new XSSFColor(new java.awt.Color(132, 32, 41), colorMap); // #842029
            XSSFColor errorBgColor = new XSSFColor(new java.awt.Color(248, 215, 218), colorMap); // #F8D7DA
            
            XSSFColor borderColor = new XSSFColor(new java.awt.Color(229, 229, 229), colorMap); // #E5E5E5

            // Fonts
            XSSFFont fontHeader = (XSSFFont) workbook.createFont();
            fontHeader.setFontName("Segoe UI");
            fontHeader.setFontHeightInPoints((short) 11);
            fontHeader.setBold(true);
            fontHeader.setColor(whiteColor);

            XSSFFont fontData = (XSSFFont) workbook.createFont();
            fontData.setFontName("Segoe UI");
            fontData.setFontHeightInPoints((short) 10);
            fontData.setColor(textDarkColor);

            XSSFFont fontGray = (XSSFFont) workbook.createFont();
            fontGray.setFontName("Segoe UI");
            fontGray.setFontHeightInPoints((short) 10);
            fontGray.setColor(textGrayColor);

            XSSFFont fontAvailable = (XSSFFont) workbook.createFont();
            fontAvailable.setFontName("Segoe UI");
            fontAvailable.setFontHeightInPoints((short) 10);
            fontAvailable.setBold(true);
            fontAvailable.setColor(greenTextColor);

            XSSFFont fontActive = (XSSFFont) workbook.createFont();
            fontActive.setFontName("Segoe UI");
            fontActive.setFontHeightInPoints((short) 10);
            fontActive.setBold(true);
            fontActive.setColor(activeTextColor);

            XSSFFont fontError = (XSSFFont) workbook.createFont();
            fontError.setFontName("Segoe UI");
            fontError.setFontHeightInPoints((short) 10);
            fontError.setBold(true);
            fontError.setColor(errorTextColor);

            // Styles
            // 1. Header Style
            XSSFCellStyle headerStyle = (XSSFCellStyle) workbook.createCellStyle();
            headerStyle.setFont(fontHeader);
            headerStyle.setFillForegroundColor(headerBgColor);
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderBottom(BorderStyle.MEDIUM);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);
            headerStyle.setTopBorderColor(borderColor);
            headerStyle.setBottomBorderColor(headerBgColor);
            headerStyle.setLeftBorderColor(borderColor);
            headerStyle.setRightBorderColor(borderColor);

            // 2. Phone Styles
            XSSFCellStyle phoneEvenStyle = (XSSFCellStyle) workbook.createCellStyle();
            phoneEvenStyle.setFont(fontData);
            phoneEvenStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            phoneEvenStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorders(phoneEvenStyle, borderColor);

            XSSFCellStyle phoneOddStyle = (XSSFCellStyle) workbook.createCellStyle();
            phoneOddStyle.setFont(fontData);
            phoneOddStyle.setFillForegroundColor(oddRowBgColor);
            phoneOddStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            phoneOddStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            phoneOddStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorders(phoneOddStyle, borderColor);

            // 3. Status Styles
            XSSFCellStyle statusAvailableStyle = (XSSFCellStyle) workbook.createCellStyle();
            statusAvailableStyle.setFont(fontAvailable);
            statusAvailableStyle.setFillForegroundColor(greenBgColor);
            statusAvailableStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            statusAvailableStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            statusAvailableStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorders(statusAvailableStyle, borderColor);

            XSSFCellStyle statusActiveStyle = (XSSFCellStyle) workbook.createCellStyle();
            statusActiveStyle.setFont(fontActive);
            statusActiveStyle.setFillForegroundColor(activeBgColor);
            statusActiveStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            statusActiveStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            statusActiveStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorders(statusActiveStyle, borderColor);

            XSSFCellStyle statusErrorStyle = (XSSFCellStyle) workbook.createCellStyle();
            statusErrorStyle.setFont(fontError);
            statusErrorStyle.setFillForegroundColor(errorBgColor);
            statusErrorStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            statusErrorStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            statusErrorStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorders(statusErrorStyle, borderColor);

            // 4. Base Row Styles (for Inactive status)
            XSSFCellStyle baseEvenStyle = (XSSFCellStyle) workbook.createCellStyle();
            baseEvenStyle.setFont(fontGray);
            baseEvenStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            baseEvenStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorders(baseEvenStyle, borderColor);

            XSSFCellStyle baseOddStyle = (XSSFCellStyle) workbook.createCellStyle();
            baseOddStyle.setFont(fontGray);
            baseOddStyle.setFillForegroundColor(oddRowBgColor);
            baseOddStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            baseOddStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            baseOddStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorders(baseOddStyle, borderColor);

            // Header Row
            Row headerRow = sheet.createRow(0);
            headerRow.setHeightInPoints(26);
            
            Cell cellPhone = headerRow.createCell(0);
            cellPhone.setCellValue("MSISDN");
            cellPhone.setCellStyle(headerStyle);

            for (int i = 0; i < systems.length; i++) {
                Cell sysHeader = headerRow.createCell(i + 1);
                sysHeader.setCellValue(getSystemDisplayName(systems[i]));
                sysHeader.setCellStyle(headerStyle);
            }

            Cell errHeader = headerRow.createCell(systems.length + 1);
            errHeader.setCellValue("Error Details");
            errHeader.setCellStyle(headerStyle);

            // Data rows
            int rowIdx = 1;
            for (CheckResultRow resultRow : results) {
                Row row = sheet.createRow(rowIdx);
                row.setHeightInPoints(20);
                
                // Determine row background color parity (even row index is odd row in 0-based data row indices)
                // Row 1 -> index 1 -> odd -> white
                // Row 2 -> index 2 -> even -> light blue (#F0F6FC)
                boolean isLightBlueRow = (rowIdx % 2 == 0);
                
                // Column 0: MSISDN
                Cell phoneCell = row.createCell(0);
                phoneCell.setCellValue(resultRow.getPhoneNumber());
                phoneCell.setCellStyle(isLightBlueRow ? phoneOddStyle : phoneEvenStyle);
                
                StringBuilder errorDetails = new StringBuilder();

                // System columns
                for (int s = 0; s < systems.length; s++) {
                    String status = resultRow.getSystems().get(systems[s]);
                    if (status == null) {
                        status = "WAITING";
                    }
                    
                    Cell cell = row.createCell(s + 1);
                    String upperStatus = status.toUpperCase();
                    
                    if ("AVAILABLE".equals(upperStatus)) {
                        cell.setCellValue("Available");
                        cell.setCellStyle(statusAvailableStyle);
                    } else if ("ACTIVE".equals(upperStatus)) {
                        cell.setCellValue("Active");
                        cell.setCellStyle(statusActiveStyle);
                    } else if ("ERROR".equals(upperStatus)) {
                        cell.setCellValue("Error");
                        cell.setCellStyle(statusErrorStyle);
                        
                        String sysName = getSystemDisplayName(systems[s]);
                        String rawErr = resultRow.getRawResponses().get(systems[s]);
                        if (rawErr == null) rawErr = "Unknown Error";
                        if (errorDetails.length() > 0) {
                            errorDetails.append("; ");
                        }
                        errorDetails.append("[").append(sysName).append("] ").append(rawErr);
                    } else {
                        // For Inactive or other statuses, show "-" and use base style (matching alternating rows)
                        cell.setCellValue("-");
                        cell.setCellStyle(isLightBlueRow ? baseOddStyle : baseEvenStyle);
                    }
                }
                
                // Error Details column
                Cell errCell = row.createCell(systems.length + 1);
                if (errorDetails.length() > 0) {
                    errCell.setCellValue(errorDetails.toString());
                    errCell.setCellStyle(isLightBlueRow ? baseOddStyle : baseEvenStyle);
                } else {
                    errCell.setCellValue("-");
                    errCell.setCellStyle(isLightBlueRow ? baseOddStyle : baseEvenStyle);
                }
                
                rowIdx++;
            }

            // Set Autofilter
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, results.size(), 0, systems.length + 1));

            // Freeze header and first column (MSISDN)
            sheet.createFreezePane(1, 1);

            // Update Table Headers
            table.updateHeaders();

            // Set column widths
            // Col 0: MSISDN (~18 chars)
            sheet.setColumnWidth(0, 5000);
            
            // System columns (~18 chars each)
            for (int i = 0; i < systems.length; i++) {
                int colIdx = i + 1;
                sheet.autoSizeColumn(colIdx);
                int width = sheet.getColumnWidth(colIdx) + 2000;
                if (width < 18 * 256) {
                    width = 18 * 256;
                }
                sheet.setColumnWidth(colIdx, width);
            }

            // Error Details column auto sizing
            int errColIdx = systems.length + 1;
            sheet.autoSizeColumn(errColIdx);
            int errWidth = sheet.getColumnWidth(errColIdx) + 2000;
            if (errWidth < 25 * 256) {
                errWidth = 25 * 256;
            }
            sheet.setColumnWidth(errColIdx, errWidth);

            // Flush and write output file
            try (FileOutputStream fos = new FileOutputStream(jobManager.getResultsExcelFile())) {
                workbook.write(fos);
            }
        } finally {
            // Clean up workbook resources
            workbook.close();
        }
    }

    private void setBorders(XSSFCellStyle style, XSSFColor borderColor) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setTopBorderColor(borderColor);
        style.setBottomBorderColor(borderColor);
        style.setLeftBorderColor(borderColor);
        style.setRightBorderColor(borderColor);
    }

    private String getSystemDisplayName(String systemCode) {
        if (systemCode == null) return "";
        switch (systemCode) {
            case "ocs_ocs": return "OCS OCS";
            case "ocs_iot": return "OCS IOT";
            case "wom": return "WOM-OCS";
            case "wom_iot": return "WOM-IOT";
            case "billing": return "Billing";
            case "crm": return "CRM";
            case "brm": return "BRM";
            case "inventory": return "Inventory";
            default: return systemCode.toUpperCase();
        }
    }

    private String getCellStringValue(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double numericValue = cell.getNumericCellValue();
                if (numericValue == Math.floor(numericValue) && !Double.isInfinite(numericValue)) {
                    return String.format("%.0f", numericValue);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return formatter.formatCellValue(cell);
    }
}
