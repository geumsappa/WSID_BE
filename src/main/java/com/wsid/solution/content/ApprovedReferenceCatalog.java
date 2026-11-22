package com.wsid.solution.content;

import com.wsid.ai.dto.SolutionCandidateReference;
import com.wsid.ai.dto.VerifiedReference;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Reviewed immutable deployment artifact. Candidate identity alone never grants approval. */
@Component
public class ApprovedReferenceCatalog {
    private record Entry(String version, VerifiedReference reference) {}
    private final Map<String, Entry> entries;
    public ApprovedReferenceCatalog(@Value("${wsid.content.approved-references:classpath:content/approved-references.json}") Resource resource) {
        try (var input = resource.getInputStream()) {
            var node = new ObjectMapper().readTree(input);
            if (!node.isArray()) throw new IllegalArgumentException();
            var values = new HashMap<String, Entry>();
            for (var item : node) {
                for (String key : List.of("code", "version", "title", "excerpt", "source", "checkedAt"))
                    if (!item.path(key).isString() || item.path(key).asString().isBlank()) throw new IllegalArgumentException();
                if (!item.path("reviewed").isBoolean() || !item.path("reviewed").asBoolean()) throw new IllegalArgumentException();
                java.time.LocalDate.parse(item.path("checkedAt").asString());
                var uri = java.net.URI.create(item.path("source").asString());
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
                var identity = new SolutionCandidateReference(item.path("code").asString(), item.path("version").asString());
                var ref = new VerifiedReference(identity.code(), item.path("title").asString(), item.path("excerpt").asString());
                if (values.putIfAbsent(identity.code(), new Entry(identity.version(), ref)) != null) throw new IllegalArgumentException();
            }
            entries = Map.copyOf(values);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("승인 근거 파일 형식이 올바르지 않아요."); }
    }
    public List<VerifiedReference> approved(List<SolutionCandidateReference> candidates) {
        var result = new ArrayList<VerifiedReference>();
        for (var candidate : candidates) {
            var entry = entries.get(candidate.code());
            if (entry != null && entry.version().equals(candidate.version())) result.add(entry.reference());
        }
        return List.copyOf(result);
    }
}
