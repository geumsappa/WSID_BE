package com.wsid.content;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class GuideCatalog {
    private final List<FraudType> fraudTypes;
    private final List<Card> cards;
    private final List<Institution> institutions;

    public GuideCatalog(ObjectMapper mapper,
            @Value("${wsid.guides.catalog:classpath:content/guides.yml}") Resource resource) {
        var yaml = new YamlMapFactoryBean();
        yaml.setResources(resource);
        var data = mapper.convertValue(yaml.getObject(), CatalogData.class);
        fraudTypes = published(data.fraudTypes(), item -> present(item.content().code(), item.content().name(),
                item.content().description()) && date(item.checkedAt())
                && populated(item.content().references()) && item.content().examples() != null
                && item.content().warningSignals() != null && item.content().preventionTips() != null);
        cards = published(data.responseCards(), item -> present(item.content().code(), item.content().title(),
                item.content().priority()) && date(item.content().reviewedAt()) && populated(item.content().actions())
                && populated(item.content().sourceReferences()) && item.content().requiredMaterials() != null
                && item.content().institutionCodes() != null);
        institutions = published(data.institutions(), item -> present(item.content().code(), item.content().name(),
                item.content().role(), item.source()) && date(item.content().reviewedAt()));
        Set<String> institutionCodes = Set.copyOf(institutions.stream().map(Institution::code).toList());
        if (cards.stream().anyMatch(card -> !institutionCodes.containsAll(card.institutionCodes()))) {
            throw new IllegalStateException("공개 대응 카드에 미등록 기관 코드가 있어요.");
        }
    }

    private static boolean present(String... values) {
        return Arrays.stream(values).allMatch(value -> value != null && !value.isBlank());
    }

    private static boolean populated(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private static boolean date(String value) {
        if (value == null) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static <T extends Coded> List<T> published(List<Entry<T>> entries, Predicate<Entry<T>> valid) {
        var codes = new HashSet<String>();
        return entries.stream().filter(Entry::reviewed).map(entry -> {
            if (entry.content() == null || !valid.test(entry) || !codes.add(entry.content().code())) {
                throw new IllegalStateException("공개 가이드의 필수 값이 누락되었거나 코드가 중복돼요.");
            }
            return entry.content();
        }).toList();
    }

    public List<FraudTypeSummary> fraudTypes() {
        return fraudTypes.stream().map(type -> new FraudTypeSummary(type.code(), type.name(), type.description())).toList();
    }

    public FraudType fraudType(String code) {
        return fraudTypes.stream().filter(type -> type.code().equals(code)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.GUIDE_NOT_FOUND));
    }

    public List<Card> cards(Set<String> codes) {
        return cards.stream().filter(card -> codes.isEmpty() || codes.contains(card.code())).toList();
    }

    public List<Institution> institutions() {
        return institutions;
    }

    interface Coded { String code(); }
    record Entry<T>(boolean reviewed, String source, String checkedAt, T content) {}
    record CatalogData(List<Entry<FraudType>> fraudTypes, List<Entry<Card>> responseCards,
            List<Entry<Institution>> institutions) {}
    public record FraudTypeSummary(String code, String name, String description) {}
    public record FraudType(String code, String name, String description, List<String> examples,
            List<String> warningSignals, List<String> preventionTips, List<JsonNode> references) implements Coded {}
    public record Card(String code, String title, String priority, List<String> actions, List<String> requiredMaterials,
            List<String> institutionCodes, List<JsonNode> sourceReferences, String reviewedAt) implements Coded {}
    public record Institution(String code, String name, String role, String phone, String officialUrl,
            String reviewedAt) implements Coded {}
}
