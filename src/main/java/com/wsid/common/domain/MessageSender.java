package com.wsid.common.domain;

/** 메시지를 보낸 쪽. training_messages.sender, solution_messages.sender (VARCHAR(10)) */
public enum MessageSender {
    USER,
    AI,
    SYSTEM
}
