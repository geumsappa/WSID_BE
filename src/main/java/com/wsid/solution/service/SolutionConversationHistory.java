package com.wsid.solution.service;

import com.wsid.ai.dto.SolutionAssistHistoryTurn;
import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.solution.domain.SolutionMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 저장된 상담 메시지를 assist 요청의 history로 바꿔요. 저장 유형은 sender·input_kind·purpose로 구분하고
 * 표시 문구로 추측하지 않아요(예전 고정 문구 행만 예외로 건너뛰어요).
 *
 * <ul>
 *   <li>상황 설명(SOL-01, SOL-03 텍스트 입력), 자유 질문(QUESTION) → USER</li>
 *   <li>AI 답변·답변 불가 안내(purpose=ANSWER) → AI</li>
 *   <li>사진(purpose=PHOTO) → USER, 내용은 assist_context("[사진 · 종류] 요약 … 사진 속 글자 …")</li>
 *   <li>선택형 답·사실 수정·확인 질문 답·SYSTEM 안내 → 넣지 않음(facts·confirmedConditions로 이미 가요)</li>
 * </ul>
 * 최근 10턴만 보내되, 사진은 대화가 길어져도 빠지지 않게 최근 2개를 항상 넣어요. 턴은 2000자로 잘라요.
 * 이번 요청의 질문은 AI 호출 뒤에 저장되므로 읽는 시점에는 없어서 자동으로 빠져요.
 */
final class SolutionConversationHistory {
    static final int MAX_TURNS = 10;
    static final int MAX_PHOTOS = 2;
    static final int MAX_TURN_LENGTH = 2_000;
    /** 원문을 저장하기 전의 고정 문구. 이런 옛 행은 대화 내용이 아니라서 history에 넣지 않아요. */
    private static final Set<String> LEGACY_TEXT = Set.of(
            "질문을 접수했습니다.", "피해 설명을 제출했습니다.", "사진 속 내용을 제출했습니다.");

    private SolutionConversationHistory() {}

    /** 넣을 턴이 없으면 null이라 history 없는 기존 요청과 같아요. */
    static List<SolutionAssistHistoryTurn> from(List<SolutionMessage> messages) {
        var turns = new ArrayList<SolutionAssistHistoryTurn>();
        var photos = new ArrayList<Integer>();
        for (SolutionMessage message : messages) {
            String purpose = message.getPurpose();
            if (SolutionMessage.PHOTO_PURPOSE.equals(purpose)) {
                if (blank(message.getAssistContext())) continue;
                photos.add(turns.size());
                turns.add(turn("USER", message.getAssistContext()));
            } else if (message.getSender() == MessageSender.AI && SolutionMessage.ANSWER_PURPOSE.equals(purpose)) {
                if (!blank(message.getSafeContent())) turns.add(turn("AI", message.getSafeContent()));
            } else if (message.getSender() == MessageSender.USER && message.getInputKind() == InputKind.TEXT
                    && (purpose == null || "QUESTION".equals(purpose))) {
                String text = message.getSafeContent();
                if (blank(text) || LEGACY_TEXT.contains(text.strip()) || legacyPhoto(text)) continue;
                turns.add(turn("USER", text));
            }
        }
        if (turns.isEmpty()) return null;
        var keep = new TreeSet<Integer>(photos.subList(Math.max(0, photos.size() - MAX_PHOTOS), photos.size()));
        for (int i = turns.size() - 1; i >= 0 && keep.size() < MAX_TURNS; i--) {
            if (!photos.contains(i)) keep.add(i);
        }
        return keep.stream().map(turns::get).toList();
    }

    private static SolutionAssistHistoryTurn turn(String role, String text) {
        String value = text.strip();
        if (value.length() > MAX_TURN_LENGTH) {
            int end = MAX_TURN_LENGTH;
            if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
            value = value.substring(0, end);
        }
        return new SolutionAssistHistoryTurn(role, value);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** 예전 사진 행("사진(메신저 대화)을 제출했습니다.")은 purpose가 없어서 문구로만 알아봐요. */
    private static boolean legacyPhoto(String text) {
        String value = text.strip();
        return value.startsWith("사진(") && value.endsWith("을 제출했습니다.");
    }
}
