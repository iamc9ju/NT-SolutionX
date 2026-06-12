package com.project.checker.service;

public interface ExternalSystemClient {
    /**
     * Checks the status of a phone number in the specified external system.
     * 
     * @param systemCode  the target system code (e.g. sysA, sysB, sysC, sysN)
     * @param phoneNumber the phone number to check
     * @return "USED" or "NOT_USED"
     * @throws Exception if connection fails or API returns error
     */
    String checkPhoneNumber(String systemCode, String phoneNumber) throws Exception;

    /**
     * Checks if the system is using mock simulation (not connected to a real API).
     * 
     * @param systemCode the system code to check
     * @return true if mock, false if connected to real API
     */
    boolean isMock(String systemCode);
}
