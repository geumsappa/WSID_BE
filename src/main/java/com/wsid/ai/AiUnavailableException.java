package com.wsid.ai;

/** AI 서비스를 쓸 수 없을 때. 서비스 계층은 이걸 잡아 검토된 문구로 이어가요. */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
