package com.wsid.solution.domain;

/** 확인한 사실의 값. 근거가 없으면 UNKNOWN으로 두고 NO로 추측하지 않아요. */
public enum FactValue {
    YES("예"),
    NO("아니요"),
    UNKNOWN("모르겠어요");

    private final String label;

    FactValue(String label) {
        this.label = label;
    }

    /** 대화 기록(safe_content)에 남기는 한글 표시. selected_code에는 코드값을 그대로 둬요. */
    public String label() {
        return label;
    }
}
