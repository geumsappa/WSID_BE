package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MockPageTemplatesTest {

    @Test
    void picksTemplateFromScenarioWords() {
        assertEquals("DELIVERY_ADDRESS", MockPageTemplates.pick("배송 담당자", "새롬택배", "택배 주소 재확인 요구", "").code());
        assertEquals("SECONDHAND_SAFE_PAY", MockPageTemplates.pick("판매자", "", "중고거래 안전결제 유도", "").code());
        assertEquals("LOAN_APPLY", MockPageTemplates.pick("상담원", "가상캐피탈", "저금리 대출 안내", "").code());
        assertEquals("CASE_LOOKUP", MockPageTemplates.pick("수사관", "서울중앙지검", "검찰 사칭 사건 조회", "").code());
        assertEquals("BANK_LOGIN", MockPageTemplates.pick("상담원", "가상은행", "이상 거래 알림", "").code());
    }

    @Test
    void cardDeliveryAndCompensationUseBankCheckNotDelivery() {
        assertEquals("BANK_LOGIN", MockPageTemplates.pick("배송원", "가상카드", "카드 배송 사칭 후 기관 사칭", "").code());
        assertEquals("BANK_LOGIN", MockPageTemplates.pick("보상 담당", "", "개인정보 유출 피해 보상금 신청", "").code());
    }

    @Test
    void oldReportsWithoutAppClickReadAsNotClicked() {
        var page = new com.wsid.report.domain.ReportEvaluation.MockPageEvaluation(true, false, false);
        assertFalse(page.clickedAppLink());
    }

    @Test
    void recruitmentWinsOverBankName() {
        assertEquals("RECRUIT_APPLICANT", MockPageTemplates.pick("채용 담당자", "기업은행", "채용 확인", "").code());
    }

    @Test
    void picksFraudTypeFromScenario() {
        var json = new tools.jackson.databind.ObjectMapper();
        var delivery = json.createObjectNode().put("title", "택배 주소 재확인 요구").put("description", "배송 보류 문자");
        delivery.putObject("persona").put("organization", "새롬택배").put("role", "배송 담당자");
        assertEquals("SMISHING", TrainingWorkflow.fraudTypeOf(delivery));
        assertEquals("DELIVERY_ADDRESS", TrainingWorkflow.templateOf(delivery).code());

        var unknown = json.createObjectNode().put("title", "확인 요청").put("description", "가상 상황");
        assertEquals("SECONDHAND_TRADE", TrainingWorkflow.fraudTypeOf(unknown)); // 정하지 못하면 기본 유형
        assertEquals("택배·문자 스미싱 예방 훈련", com.wsid.report.service.ReportService.titleOf("SMISHING"));
        assertEquals("금융사기 예방 훈련", com.wsid.report.service.ReportService.titleOf("UNKNOWN"));
    }

    @Test
    void unknownScenarioFallsBackToGeneric() {
        var template = MockPageTemplates.pick("담당자", "", "확인 요청", null);
        assertEquals("GENERIC", template.code());
        assertEquals("IDENTITY_CHECK", template.screenCode());
    }
}
