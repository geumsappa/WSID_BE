package com.wsid.solution.domain;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Opaque pagination position, not an authorization token or tamper-proof signature. */
public record SolutionCursor(Instant completedAt, long id) {
    public SolutionCursor { if (completedAt == null || id <= 0) throw invalid(); }
    public String encode() { return Base64.getUrlEncoder().withoutPadding().encodeToString((completedAt+"|"+id).getBytes(StandardCharsets.UTF_8)); }
    public static SolutionCursor decode(String value) {
        if (value == null || value.length() > 200 || !value.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);
            String[] parts=raw.split("\\|",-1);
            if(parts.length!=2 || !parts[1].matches("[1-9][0-9]{0,18}")) throw invalid();
            var result=new SolutionCursor(Instant.parse(parts[0]),Long.parseLong(parts[1]));
            if(!result.encode().equals(value)) throw invalid();
            return result;
        } catch(RuntimeException e) {throw invalid();}
    }
    private static ApiException invalid() {return new ApiException(ErrorCode.INVALID_REQUEST);}
}
