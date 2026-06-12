package com.project.checker.model;

import java.util.ArrayList;
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
    private List<CheckResultRow> resultsPreview = new ArrayList<>();
    private List<String> importLogs = new ArrayList<>();

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(String uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public String getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(String completedAt) {
        this.completedAt = completedAt;
    }

    public int getTotalNumbers() {
        return totalNumbers;
    }

    public void setTotalNumbers(int totalNumbers) {
        this.totalNumbers = totalNumbers;
    }

    public int getProcessedNumbers() {
        return processedNumbers;
    }

    public void setProcessedNumbers(int processedNumbers) {
        this.processedNumbers = processedNumbers;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<CheckResultRow> getResultsPreview() {
        return resultsPreview;
    }

    public void setResultsPreview(List<CheckResultRow> resultsPreview) {
        this.resultsPreview = resultsPreview;
    }

    public List<String> getImportLogs() {
        return importLogs;
    }

    public void setImportLogs(List<String> importLogs) {
        this.importLogs = importLogs;
    }
}
