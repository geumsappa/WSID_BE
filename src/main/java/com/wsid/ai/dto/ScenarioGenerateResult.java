package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.regex.Pattern;

/** AI-03 응답 data. 형식 검증은 콘텐츠 검토나 템플릿 등록 여부 확인을 대신하지 않아요. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioGenerateResult(Scenario scenario, String mockPageTemplateCode) {

    private static final Pattern TEMPLATE_CODE = Pattern.compile("[A-Z0-9_]{1,50}");

    public ScenarioGenerateResult {
        if (scenario == null || mockPageTemplateCode == null
                || !TEMPLATE_CODE.matcher(mockPageTemplateCode).matches()) {
            throw new IllegalArgumentException("시나리오 결과 형식이 올바르지 않아요.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scenario(String title, String description) {
        public Scenario {
            if (title == null || title.isBlank() || title.length() > 100
                    || description == null || description.isBlank() || description.length() > 1_000) {
                throw new IllegalArgumentException("시나리오 제목이나 설명이 올바르지 않아요.");
            }
        }

        @Override
        public String toString() {
            return "Scenario[titleLength=" + title.length()
                    + ", descriptionLength=" + description.length() + "]";
        }
    }

    @Override
    public String toString() {
        return "ScenarioGenerateResult[scenario=" + scenario
                + ", mockPageTemplateCode=" + mockPageTemplateCode + "]";
    }
}
