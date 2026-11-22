package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TrainingSenderNameTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exposesScenarioOrganizationAndRoleWithoutSpoilingTheRolePlay() {
        var scenario = json.createObjectNode().put("title", "채용 확인 훈련").put("description", "가상 채용 상황");
        scenario.putObject("persona").put("organization", "기업은행").put("role", "채용 담당자 사칭").put("name", "김민수");
        var summary = TrainingWorkflow.summary(scenario);
        assertEquals("기업은행 채용 담당자", summary.senderName());
        assertEquals("기업은행", summary.organizationName());
    }

    @Test
    void avoidsDuplicatingOrganizationInRoleAndSupportsOldScenarios() {
        var scenario = json.createObjectNode().put("title", "훈련").put("description", "가상 상황");
        assertEquals("상대방", TrainingWorkflow.summary(scenario).senderName());
        scenario.putObject("persona").put("organization", "기업은행").put("role", "기업은행 채용 담당자");
        assertEquals("기업은행 채용 담당자", TrainingWorkflow.summary(scenario).senderName());
    }

    @Test
    void fictionalOrganizationKeepsItsNameAndPersonalContactUsesName() {
        var scenario = json.createObjectNode().put("title", "훈련").put("description", "가상 상황");
        scenario.putObject("persona").put("organization", "새솔은행(가상)").put("role", "담당자");
        assertEquals("새솔은행 담당자", TrainingWorkflow.summary(scenario).senderName());
        scenario.putObject("persona").put("organization", "없음").put("role", "가족 사칭").put("name", "김민수");
        assertEquals("김민수", TrainingWorkflow.summary(scenario).senderName());
        assertNull(TrainingWorkflow.summary(scenario).organizationName());
    }

    @Test
    void individualCounterpartDoesNotRepeatRole() {
        var scenario = json.createObjectNode().put("title", "선풍기 거래").put("description", "가상 상황");
        scenario.putObject("persona").put("organization", "개인 구매자").put("role", "구매자");
        assertEquals("구매자", TrainingWorkflow.summary(scenario).senderName());
        assertNull(TrainingWorkflow.summary(scenario).organizationName());
        scenario.putObject("persona").put("organization", "한결배송 배송팀").put("role", "배송팀");
        assertEquals("한결배송 배송팀", TrainingWorkflow.summary(scenario).senderName());
    }
}
