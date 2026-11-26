package com.wsid.ai.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record DiscoveryRequest(List<DiscoveryTurn> conversation, Map<String, String> knownFacts,
        String currentInput, @JsonIgnore UUID requestId) {}
