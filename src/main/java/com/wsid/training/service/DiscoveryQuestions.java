package com.wsid.training.service;

import com.wsid.training.domain.ContextFacts;

public final class DiscoveryQuestions {
    private DiscoveryQuestions() {}
    public static String fallbackFor(ContextFacts facts) {
        var values = facts.values();
        if (!values.containsKey("activity")) return ReviewedTexts.DISCOVERY_ACTIVITY;
        if (!values.containsKey("role")) return ReviewedTexts.DISCOVERY_ROLE;
        if (!values.containsKey("channel")) return ReviewedTexts.DISCOVERY_CHANNEL;
        if (!values.containsKey("item")) return ReviewedTexts.DISCOVERY_ITEM;
        return ReviewedTexts.DISCOVERY_OTHER;
    }
}
