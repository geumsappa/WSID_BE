package com.wsid.solution.service;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.solution.domain.FactValue;
import com.wsid.solution.domain.SolutionFact;
import com.wsid.solution.domain.SolutionMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SolutionConversationHistoryTest {
    static final Instant NOW = Instant.EPOCH;

    static SolutionMessage question(int seq, String text) {
        return SolutionMessage.assist(1L, seq, "QUESTION", null, text, null, null, UUID.randomUUID(), List.of(), NOW);
    }

    static SolutionMessage photo(int seq, String context) {
        return SolutionMessage.photo(1L, seq, "사진(메신저 대화)을 제출했습니다.", context, UUID.randomUUID(), NOW);
    }

    @Test void mapsDescriptionQuestionAnswerAndPhotoInOrderAndSkipsStructuredRows() {
        var messages = List.of(
                SolutionMessage.of(1L, MessageSender.USER, InputKind.TEXT, 1, "중고거래 판매자가 선입금을 요구해요", UUID.randomUUID(), NOW),
                SolutionMessage.of(1L, MessageSender.SYSTEM, InputKind.TEXT, 2, "개인정보를 전달한 적이 있나요?", null, NOW),
                SolutionMessage.choice(1L, 3, SolutionFact.SHARED_INFORMATION, FactValue.NO, UUID.randomUUID(), NOW),
                photo(4, "[사진 · 메신저 대화] 요약: 상대가 계좌를 보냈어요."),
                question(5, "이 계좌로 보내도 돼요?"),
                SolutionMessage.aiReply(1L, 6, "사진을 보면 보내지 마세요.", List.of("REF_A"), NOW),
                SolutionMessage.correction(1L, 7, SolutionFact.CONTACT_CONTINUES, FactValue.YES, NOW));
        var turns = SolutionConversationHistory.from(messages);
        assertEquals(List.of("USER", "USER", "USER", "AI"), turns.stream().map(t -> t.role()).toList());
        assertEquals(List.of("중고거래 판매자가 선입금을 요구해요", "[사진 · 메신저 대화] 요약: 상대가 계좌를 보냈어요.",
                "이 계좌로 보내도 돼요?", "사진을 보면 보내지 마세요."), turns.stream().map(t -> t.content()).toList());
    }

    @Test void skipsLegacyFixedTextsAndReturnsNullWhenNothingToSend() {
        var legacy = List.of(
                SolutionMessage.of(1L, MessageSender.USER, InputKind.TEXT, 1, "피해 설명을 제출했습니다.", UUID.randomUUID(), NOW),
                SolutionMessage.of(1L, MessageSender.USER, InputKind.TEXT, 2, "사진(문자 메시지)을 제출했습니다.", UUID.randomUUID(), NOW));
        assertNull(SolutionConversationHistory.from(legacy));
    }

    @Test void keepsLastTenTurnsButNeverDropsTheLatestTwoPhotos() {
        var messages = new ArrayList<SolutionMessage>();
        messages.add(photo(1, "[사진 · 문자 메시지] 첫 사진"));
        messages.add(photo(2, "[사진 · 메신저 대화] 두 번째 사진"));
        for (int i = 0; i < 12; i++) messages.add(question(3 + i, "질문 " + i));
        var turns = SolutionConversationHistory.from(messages);
        assertEquals(SolutionConversationHistory.MAX_TURNS, turns.size());
        assertEquals("[사진 · 문자 메시지] 첫 사진", turns.get(0).content());
        assertEquals("[사진 · 메신저 대화] 두 번째 사진", turns.get(1).content());
        assertEquals("질문 4", turns.get(2).content());
        assertEquals("질문 11", turns.get(9).content());
    }

    @Test void cutsLongTurnsToContractLength() {
        var turns = SolutionConversationHistory.from(List.of(question(1, "가".repeat(2_500))));
        assertEquals(SolutionConversationHistory.MAX_TURN_LENGTH, turns.get(0).content().length());
    }
}
