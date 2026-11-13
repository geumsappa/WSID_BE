package com.wsid.ai.dto;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record DiscoveryEnvelope(Boolean success, DiscoveryResult data, Object error) {}
