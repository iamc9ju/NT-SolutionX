package com.project.checker.service;

import com.project.checker.model.JobMetadata;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class JobManager {

    private static final Logger LOGGER = Logger.getLogger(JobManager.class.getName());

    // Inject AppConfig to guarantee config.properties is loaded before we read System properties below.
    @javax.inject.Inject
    private AppConfig appConfig;

    private File activeJobDir;
    private File metadataFile;
    private File resultsExcelFile;
    private File inputExcelFile;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Jsonb jsonb = JsonbBuilder.create();

    @PostConstruct
    public void init() {
        String storagePath = System.getProperty("number.checker.storage.path");
        if (storagePath == null || storagePath.trim().isEmpty()) {
            storagePath = System.getenv("NUMBER_CHECKER_STORAGE_PATH");
        }
        if (storagePath == null || storagePath.trim().isEmpty()) {
            // Auto-detect: use JBoss/WildFly standard data directory (works on any server, no config needed)
            String jbossDataDir = System.getProperty("jboss.server.data.dir");
            if (jbossDataDir != null && !jbossDataDir.trim().isEmpty()) {
                storagePath = jbossDataDir + "/number-checker";
            } else {
                // Last resort fallback if not running on JBoss
                storagePath = new File(System.getProperty("user.dir"), "data/number-checker").getAbsolutePath();
            }
        }

        activeJobDir = new File(storagePath, "active_job");
        if (!activeJobDir.exists()) {
            boolean created = activeJobDir.mkdirs();
            if (created) {
                LOGGER.log(Level.INFO, "Created active job storage directory: {0}", activeJobDir.getAbsolutePath());
            }
        }

        metadataFile = new File(activeJobDir, "metadata.json");
        resultsExcelFile = new File(activeJobDir, "results.xlsx");
        inputExcelFile = new File(activeJobDir, "input.xlsx");
        
        LOGGER.log(Level.INFO, "JobManager initialized with storage path: {0}", activeJobDir.getAbsolutePath());
    }

    public void clearActiveJob() {
        rwLock.writeLock().lock();
        try {
            deleteFileIfExists(metadataFile);
            deleteFileIfExists(resultsExcelFile);
            deleteFileIfExists(inputExcelFile);
            LOGGER.info("Active job directory cleared.");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void deleteFileIfExists(File file) {
        if (file.exists()) {
            boolean deleted = file.delete();
            if (!deleted) {
                LOGGER.log(Level.WARNING, "Failed to delete file: {0}", file.getAbsolutePath());
            }
        }
    }

    public File getInputExcelFile() {
        return inputExcelFile;
    }

    public File getResultsExcelFile() {
        return resultsExcelFile;
    }

    public JobMetadata getMetadata() {
        rwLock.readLock().lock();
        try {
            if (!metadataFile.exists()) {
                return null;
            }
            try {
                byte[] bytes = Files.readAllBytes(metadataFile.toPath());
                String jsonContent = new String(bytes, "UTF-8");
                return jsonb.fromJson(jsonContent, JobMetadata.class);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to read job metadata", e);
                return null;
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void saveMetadata(JobMetadata metadata) {
        rwLock.writeLock().lock();
        try {
            try {
                String jsonContent = jsonb.toJson(metadata);
                Files.write(metadataFile.toPath(), jsonContent.getBytes("UTF-8"),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to write job metadata", e);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
