package com.project.checker.model;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class CheckResultRow {
    private String phoneNumber;
    private Map<String, String> systems = new ConcurrentHashMap<>();
    private Map<String, String> rawResponses = new ConcurrentHashMap<>();

    public CheckResultRow() {
    }

    public CheckResultRow(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public Map<String, String> getSystems() {
        return systems;
    }

    public void setSystems(Map<String, String> systems) {
        this.systems = systems;
    }

    public Map<String, String> getRawResponses() {
        return rawResponses;
    }

    public void setRawResponses(Map<String, String> rawResponses) {
        this.rawResponses = rawResponses;
    }
}
