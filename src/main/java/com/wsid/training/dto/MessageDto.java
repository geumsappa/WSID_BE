package com.wsid.training.dto;

import com.wsid.common.domain.MessageSender;
import com.wsid.training.domain.TextSource;

/**
 * 이번 턴에 화면에 보여줄 메시지.
 *
 * @param source AI가 만든 대사인지(AI), 팀이 검토한 문구인지(REVIEWED)
 */
public record MessageDto(MessageSender sender, String text, TextSource source) {

    public static MessageDto reviewed(MessageSender sender, String text) {
        return new MessageDto(sender, text, TextSource.REVIEWED);
    }

    public static MessageDto fromAi(String text) {
        return new MessageDto(MessageSender.AI, text, TextSource.AI);
    }
}
