package com.project.checker.service;

import javax.annotation.PostConstruct;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads config.properties from the classpath (bundled inside the WAR)
 * and injects each property into System properties — so the existing
 * configureSystemUrl() and JobManager code can read them without any changes.
 *
 * Priority order (highest → lowest):
 *   1. JVM -D flags (already set on server)
 *   2. config.properties (this file, bundled in WAR)
 *   3. Environment variables
 *   4. Hardcoded fallback defaults in each class
 */
@Singleton
@Startup
public class AppConfig {

    private static final Logger LOGGER = Logger.getLogger(AppConfig.class.getName());
    private static final String CONFIG_FILE = "/config.properties";

    @PostConstruct
    public void init() {
        Properties props = new Properties();

        try (InputStream is = AppConfig.class.getResourceAsStream(CONFIG_FILE)) {
            if (is == null) {
                LOGGER.warning("config.properties not found in classpath — using environment variables / defaults.");
                return;
            }
            props.load(is);
            LOGGER.info("Loaded config.properties from classpath.");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to read config.properties", e);
            return;
        }

        // Inject each property into System properties,
        // but ONLY if a -D flag has not already been set (so server overrides still work).
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key, "").trim();
            if (value.isEmpty()) continue;                         // skip blank lines
            if (value.startsWith("#")) continue;                   // skip commented-out values

            if (System.getProperty(key) == null) {
                System.setProperty(key, value);
                LOGGER.log(Level.INFO, "Config set from properties file: {0} = {1}",
                        new Object[]{key, key.toLowerCase().contains("password") ? "****" : value});
            } else {
                LOGGER.log(Level.INFO, "Config kept from JVM -D flag: {0}", key);
            }
        }
    }
}
