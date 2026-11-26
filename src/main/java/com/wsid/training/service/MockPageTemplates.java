package com.wsid.training.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 시나리오 내용(상대 역할·기관·제목·설명)으로 모의 페이지 종류와 사기 유형(fraudTypeCode)을 골라요.
 * 화면 모양은 프론트 설정표(src/features/simulation/data/mockPageTemplates.ts)가 그리고,
 * 백엔드는 어떤 화면인지(templateCode)와 단계(screenCode)만 알려 줘요. 코드 이름은 프론트와 같아야 해요.
 * 모델이 만든 URL·HTML은 받지 않고, 여기 정해진 코드만 내보내요.
 */
final class MockPageTemplates {
    /** fraudTypeCode가 null이면 유형을 정하지 못한 것 (호출하는 쪽이 기본 유형을 써요) */
    record Template(String code, String title, String screenCode, String fraudTypeCode) {}

    static final Template RECRUIT = new Template("RECRUIT_APPLICANT", "지원자 확인", "APPLICANT_CHECK", "JOB_SCAM");
    static final Template DELIVERY = new Template("DELIVERY_ADDRESS", "배송지 재확인", "ADDRESS_CHECK", "SMISHING");
    static final Template SECONDHAND = new Template("SECONDHAND_SAFE_PAY", "안전결제 확인", "PAY_CHECK", "SECONDHAND_TRADE");
    static final Template LOAN = new Template("LOAN_APPLY", "대출 한도 조회", "LIMIT_CHECK", "LOAN_FRAUD");
    static final Template CASE = new Template("CASE_LOOKUP", "사건 조회", "CASE_CHECK", "INSTITUTION_IMPERSONATION");
    static final Template BANK = new Template("BANK_LOGIN", "계정 보호 본인 확인", "LOGIN_CHECK", "INSTITUTION_IMPERSONATION");
    static final Template GENERIC = new Template("GENERIC", "본인 확인", "IDENTITY_CHECK", null);

    private record Rule(Pattern pattern, Template template) {}

    /** 위에서부터 먼저 맞는 규칙을 써요. 예: "기업은행 채용 담당자"는 은행보다 채용이 먼저라 지원자 확인이 돼요. */
    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("채용|지원자|면접|구인|구직|알바|아르바이트|인사팀"), RECRUIT),
            // 카드 배송 사칭·피해 보상금·전산 장애 앱 재설치는 택배가 아니라 금융 확인 화면이 어울려요
            new Rule(Pattern.compile("카드\\s*배송|피해\\s*보상|보상금|환급금|전산\\s*장애|앱\\s*(업데이트|재설치)"), BANK),
            new Rule(Pattern.compile("택배|배송|우체국|물류|운송장"), DELIVERY),
            new Rule(Pattern.compile("중고|안전결제|직거래|마켓|판매자|구매자"), SECONDHAND),
            new Rule(Pattern.compile("대출|한도|캐피탈|저금리|대환"), LOAN),
            new Rule(Pattern.compile("검찰|경찰|법원|사건|수사|금융감독원|금감원"), CASE),
            new Rule(Pattern.compile("은행|뱅크|카드|금융|보안|계정"), BANK));

    /** 넘긴 글(역할, 기관, 제목, 설명 순)을 모두 합쳐서 규칙에 맞춰 봐요. 아무것도 안 맞으면 GENERIC. */
    static Template pick(String... texts) {
        String text = String.join(" ", java.util.Arrays.stream(texts).map(t -> t == null ? "" : t).toList());
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) return rule.template();
        }
        return GENERIC;
    }

    private MockPageTemplates() {}
}
