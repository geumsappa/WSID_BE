package com.wsid.solution.content;

import static org.junit.jupiter.api.Assertions.*;
import com.wsid.content.GuideCatalog;
import com.wsid.solution.domain.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class GuideResponseCardCatalogTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void productionContextHasOneCatalogAndBothRequiredCards() {
        new ApplicationContextRunner().withUserConfiguration(CatalogConfig.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(ResponseCardCatalog.class).size());
            var catalog = context.getBean(ResponseCardCatalog.class);
            var guides = context.getBean(GuideCatalog.class);
            assertEquals(2, guides.cards(Set.of()).size());
            assertEquals(6, guides.institutions().size());
            for (String code : List.of("TRANSFER_RESPONSE", "MALICIOUS_APP_RESPONSE")) {
                var card = catalog.findReviewed(code).orElseThrow();
                var guide = guides.cards(Set.of(code)).getFirst();
                assertEquals(guide.title(), card.title());
                assertEquals(guide.institutionCodes(), card.institutionCodes());
                assertEquals(CardPriority.URGENT, card.priority());
                assertEquals(1, card.version());
            }
            assertTrue(catalog.findReviewed("UNKNOWN").isEmpty());
        });
    }

    @Test void unreviewedEntriesStayHiddenAndYamlVersionIsPreserved() {
        var guides = load(fixture(true, 7) + """
                  - reviewed: false
                    content: {code: HIDDEN}
                institutions: []
                """);
        var catalog = new GuideResponseCardCatalog(guides);
        assertEquals(7, catalog.findReviewed("TRANSFER_RESPONSE").orElseThrow().version());
        assertTrue(catalog.findReviewed("HIDDEN").isEmpty());
        assertTrue(guides.cards(Set.of("HIDDEN")).isEmpty());
    }

    @Test void unreviewedRequiredCardDoesNotSilentlyBecomeAvailable() {
        var guides = load(fixture(false, 1) + "institutions: []\n");
        var catalog = new GuideResponseCardCatalog(guides);
        assertTrue(catalog.findReviewed("TRANSFER_RESPONSE").isEmpty());
        var facts = SolutionFacts.unknown().withChoice(SolutionFact.TRANSFERRED_MONEY, FactValue.YES);
        assertThrows(ResponseRules.MissingResponseCardException.class, () -> ResponseRules.cards(facts, catalog));
    }

    @Test void invalidReviewedVersionAndMissingInstitutionFailAtLoad() {
        assertThrows(IllegalStateException.class, () -> load(fixture(true, 0) + "institutions: []\n"));
        String missingInstitution = fixture(true, 1).replace("institutionCodes: []", "institutionCodes: [MISSING]");
        assertThrows(IllegalStateException.class, () -> load(missingInstitution + "institutions: []\n"));
    }

    @Test void publicGuideContentDoesNotExposeInternalVersion() {
        var guides = new GuideCatalog(json, new ClassPathResource("content/guides.yml"));
        var encoded = json.valueToTree(guides.cards(Set.of("TRANSFER_RESPONSE"))).get(0);
        assertFalse(encoded.has("version"));
        assertFalse(encoded.path("actions").isEmpty());
        assertFalse(encoded.path("sourceReferences").isEmpty());
    }

    private GuideCatalog load(String yaml) {
        return new GuideCatalog(json, new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private String fixture(boolean reviewed, int version) {
        return """
                fraudTypes: []
                responseCards:
                  - reviewed: %s
                    version: %d
                    content:
                      code: TRANSFER_RESPONSE
                      title: 송금 대응
                      priority: URGENT
                      actions: [공식 경로 확인]
                      requiredMaterials: []
                      institutionCodes: []
                      sourceReferences: [fixture]
                      reviewedAt: "2026-11-24"
                """.formatted(reviewed, version);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({GuideCatalog.class, GuideResponseCardCatalog.class})
    static class CatalogConfig {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
    }
}
