package com.wsid.ai.dto;

import java.util.regex.Pattern;

/** PLAN이 고른 근거 후보. 승인 여부는 Spring이 승인 원본의 코드·버전과 대조해 따로 확인해요. */
public record SolutionCandidateReference(String code, String version) {
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._-]{1,50}");

    public SolutionCandidateReference {
        if (!VerifiedReference.isValidCode(code) || version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("근거 후보 형식이 올바르지 않아요.");
        }
    }
}
