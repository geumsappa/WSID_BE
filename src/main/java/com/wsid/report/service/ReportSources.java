package com.wsid.report.service;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.report.domain.ReportSource;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Resolve credits from server-created generation evidence, never post-hoc matches. */
public final class ReportSources {
    private ReportSources() {}

    public static List<ReportSource> fromScenario(JsonNode scenario) {
        if (scenario == null || !scenario.hasNonNull("generationEvidence")) return List.of();
        JsonNode evidence = scenario.path("generationEvidence");
        JsonNode uses = evidence.path("usedForGeneration");
        if (!evidence.isObject() || evidence.toString().length() > 24000 || !uses.isArray() || uses.size() > 3) throw invalid();
        var result = new LinkedHashMap<String, ReportSource>();
        for (JsonNode use : uses) {
            String caseId = text(use, "caseId", 30, true);
            if (!caseId.matches("(?:RC|LP)-[0-9]{3}")) throw invalid();
            JsonNode source = use.path("source");
            JsonNode grant = source.path("license");
            String url = sourceUrl(text(source, "url", 2000, true));
            if (!"KOGL-1".equals(grant.path("code").asString())
                    || !"TEXT_ONLY".equals(grant.path("scope").asString())
                    || !url.equals(grant.path("evidenceUrl").asString())) throw invalid();
            boolean retrieved = false;
            for (JsonNode match : evidence.path("retrievedBeforeGeneration")) {
                if (caseId.equals(match.path("caseId").asString()) && url.equals(match.path("sourceUrl").asString())) retrieved = true;
            }
            if (!retrieved) throw invalid();
            String date = text(source, "publishedDate", 10, false);
            try { if (date != null) LocalDate.parse(date); } catch (java.time.DateTimeException error) { throw invalid(); }
            var credit = new ReportSource(text(source, "title", 300, true), text(source, "publisher", 200, true),
                    text(source, "author", 100, false), date, url, "KOGL-1");
            var previous = result.putIfAbsent(url, credit);
            if (previous != null && !previous.equals(credit)) throw invalid();
        }
        return List.copyOf(result.values());
    }

    private static String text(JsonNode node, String name, int limit, boolean required) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull()) { if (required) throw invalid(); return null; }
        if (!value.isString()) throw invalid();
        String result = value.asString().strip();
        if (result.isEmpty()) { if (required) throw invalid(); return null; }
        if (result.length() > limit || result.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return result;
    }

    private static String sourceUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 || uri.getRawFragment() != null
                    || !(host.endsWith(".go.kr") || host.equals("korea.kr") || host.endsWith(".korea.kr"))) throw invalid();
            return value;
        } catch (IllegalArgumentException error) { throw invalid(); }
    }

    private static ApiException invalid() { return new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE); }
}
