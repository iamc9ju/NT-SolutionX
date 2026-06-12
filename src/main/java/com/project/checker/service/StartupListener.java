package com.project.checker.service;

import com.project.checker.model.JobMetadata;
import javax.annotation.PostConstruct;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
@Startup
public class StartupListener {

    private static final Logger LOGGER = Logger.getLogger(StartupListener.class.getName());

    @Inject
    private JobManager jobManager;

    @PostConstruct
    public void init() {
        LOGGER.info("StartupListener initialized. Checking for interrupted active jobs...");
        try {
            JobMetadata metadata = jobManager.getMetadata();
            if (metadata != null) {
                String currentStatus = metadata.getStatus();
                if ("RUNNING".equals(currentStatus) || "PENDING".equals(currentStatus)) {
                    LOGGER.log(Level.WARNING, "Found interrupted job {0} in status {1}. Marking as FAILED.",
                            new Object[]{metadata.getJobId(), currentStatus});
                    
                    metadata.setStatus("FAILED");
                    metadata.setCompletedAt(LocalDateTime.now().toString());
                    metadata.setErrorMessage("Interrupted due to server restart or crash.");
                    
                    jobManager.saveMetadata(metadata);
                } else {
                    LOGGER.log(Level.INFO, "Active job state is clean. Current status: {0}", currentStatus);
                }
            } else {
                LOGGER.info("No active job found on startup.");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error recovering active job on startup", e);
        }
    }
}
