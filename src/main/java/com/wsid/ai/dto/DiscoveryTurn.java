package com.wsid.ai.dto;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record DiscoveryTurn(String role, String content) {}
