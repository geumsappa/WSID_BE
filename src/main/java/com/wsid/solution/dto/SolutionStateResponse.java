package com.wsid.solution.dto;

import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.solution.domain.SolutionMessage;
import com.wsid.solution.domain.SolutionStatus;
import java.util.List;
import java.util.Map;

/**
 * SOL-02 응답. 앞 6개 필드는 SOL-01 응답과 같은 형식이에요.
 * 소유자 ID·익명 UUID·client_request_id·카드 version·참조 코드는 넣지 않아요.
 */
public record SolutionStateResponse(long solutionId, SolutionStatus status, int stateVersion,
        Map<String, String> facts, List<StartSolutionResponse.Card> responseCards,
        StartSolutionResponse.Question nextQuestion, List<Message> messages, SolutionAssistant assistant) {

    /** content는 저장된 safe_content 그대로예요. 원본 피해 설명은 저장하지 않으므로 복원하지 않아요. */
    public record Message(int sequenceNo, MessageSender sender, String inputKind, String content, String selectedCode) {
        public static Message from(SolutionMessage message) {
            return new Message(message.getSequenceNo(), message.getSender(), publicKind(message.getInputKind()),
                    message.getSafeContent(), message.getSelectedCode());
        }

        /** DB의 OPTION은 공개 값 CHOICE로 바꿔요. 상담에서 쓰지 않는 ACTION은 저장 오류로 봐요. */
        static String publicKind(InputKind kind) {
            return switch (kind) {
                case TEXT -> "TEXT";
                case OPTION -> "CHOICE";
                case ACTION -> throw new IllegalStateException("상담 메시지에 지원하지 않는 입력 종류가 있어요.");
            };
        }
    }
}
