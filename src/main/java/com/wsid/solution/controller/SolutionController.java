package com.wsid.solution.controller;

import com.wsid.solution.service.SolutionImageService;
import java.util.UUID;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestParam;
import com.wsid.common.api.ApiResponse;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.dto.SolutionStateResponse;
import com.wsid.solution.dto.StartSolutionRequest;
import com.wsid.solution.dto.StartSolutionResponse;
import com.wsid.solution.dto.SendSolutionMessageRequest;
import com.wsid.solution.dto.SolutionMessageResponse;
import com.wsid.solution.dto.UpdateSolutionFactsRequest;
import com.wsid.solution.dto.CompleteSolutionResponse;
import com.wsid.solution.security.SolutionOwnerResolver;
import com.wsid.solution.service.SolutionQueryService;
import com.wsid.solution.service.StartSolutionService;
import com.wsid.solution.service.SendSolutionMessageService;
import com.wsid.solution.service.UpdateSolutionFactsService;
import com.wsid.solution.service.CompleteSolutionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 피해 대응 상담 API. 회원·비회원 모두 쓰고, 변경 요청은 CSRF 토큰이 필요해요(SolutionSecurityConfig). */
@RestController
@RequestMapping("/api/solutions")
public class SolutionController {
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");
    /** SOL-03~05 본문 파서. 후행 JSON과 중복 키를 거부해요(SOL-01은 기본 바인딩을 그대로 써요). */
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final StartSolutionService startService;
    private final SolutionQueryService queryService;
    private final SolutionOwnerResolver owners;
    private final SendSolutionMessageService messageService;
    private final UpdateSolutionFactsService factsService;
    private final CompleteSolutionService completeService;
    private SolutionImageService imageService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setImageService(SolutionImageService imageService) { this.imageService = imageService; }
    /** AI-08 도우미 말. 없으면(테스트 등) 도우미 말 없이 응답하고 화면이 기본 문장을 써요 */
    private com.wsid.solution.service.SolutionReplyService replies;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReplyService(com.wsid.solution.service.SolutionReplyService replies) { this.replies = replies; }
    /** 상담 삭제 ('저장 없이 나가기', 마이페이지 기록 삭제) */
    private com.wsid.solution.service.DeleteSolutionService deleteService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDeleteService(com.wsid.solution.service.DeleteSolutionService deleteService) { this.deleteService = deleteService; }

    public SolutionController(StartSolutionService startService, SolutionQueryService queryService,
            SolutionOwnerResolver owners, SendSolutionMessageService messageService, UpdateSolutionFactsService factsService,
            CompleteSolutionService completeService) {
        this.startService = startService;
        this.queryService = queryService;
        this.owners = owners;
        this.messageService = messageService;
        this.factsService = factsService;
        this.completeService = completeService;
    }

    /**
     * 상담용 CSRF 토큰. 비회원도 받을 수 있고, 이때 Redis 세션과 SESSION 쿠키가 생겨요.
     * 응답 형식은 GET /api/auth/csrf(회원 전용)와 같고 토큰 저장소도 같아서 어느 쪽 토큰이든 상담 요청에 쓸 수 있어요.
     */
    @GetMapping("/csrf")
    public ApiResponse<CsrfInfo> csrf(@RequestAttribute(name = "_csrf") CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(new CsrfInfo(token.getHeaderName(), token.getToken()));
    }

    /**
     * SOL-01. 본문은 선택이에요. Content-Type 없는 빈 요청은 선택형 시작이고,
     * 본문이 있으면 application/json만 받아요(그 외 415).
     */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StartSolutionResponse> start(@RequestBody(required = false) JsonNode body,
            Authentication authentication, HttpServletRequest request) {
        StartSolutionRequest input = StartSolutionRequest.from(body);
        StartSolutionResponse started = startService.start(input, () -> owners.resolve(authentication, request));
        return ApiResponse.ok(replies == null ? started : replies.withReply(started, input.description().orElse(null)));
    }

    /** SOL-02. id 형식이 틀려도(문자·0·음수·범위 초과) 같은 404로 응답해 존재 여부를 드러내지 않아요. */
    @GetMapping("/sessions/{id}")
    public ApiResponse<SolutionStateResponse> get(@PathVariable("id") String id,
            Authentication authentication, HttpServletRequest request) {
        long solutionId = parseId(id);
        return ApiResponse.ok(queryService.get(solutionId, () -> owners.findExisting(authentication, request)));
    }

    /** SOL-03: CSRF → ID → JSON 본문 → 소유권 순서. 잘못된 ID+깨진 JSON도 404예요. */
    @PostMapping("/sessions/{id}/messages")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<SolutionMessageResponse> message(@PathVariable("id") String id,
            Authentication authentication, HttpServletRequest request) throws HttpMediaTypeNotSupportedException {
        long solutionId = parseId(id);
        SendSolutionMessageRequest input = SendSolutionMessageRequest.from(strictBody(request));
        SolutionMessageResponse sent = messageService.send(solutionId, input, () -> owners.findExisting(authentication, request));
        // 상황 설명(글)으로 사실이 다시 정리됐을 때만 도우미 말을 붙여요. 질문·선택지 답은 이미 AI 답변이 와요
        boolean factText = input.isText() && input.purpose() == SendSolutionMessageRequest.Purpose.FACT_UPDATE;
        return ApiResponse.ok(replies == null || !factText ? sent : replies.withReply(solutionId, sent, input.content(), "update"));
    }

    /**
     * SOL-06: 사진 속 글자를 읽어 SOL-03 텍스트 입력처럼 반영해요. multipart 파트는 images(1~3장), requestId, stateVersion.
     * CSRF → ID → 파트 형식·파일 검증 → 소유권 순서이고, 응답 모양은 SOL-03과 같아요.
     */
    @PostMapping(path = "/sessions/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SolutionMessageResponse> images(@PathVariable("id") String id,
            @RequestParam(name = "images", required = false) List<MultipartFile> images,
            @RequestParam(name = "requestId", required = false) String requestId,
            @RequestParam(name = "stateVersion", required = false) String stateVersion,
            Authentication authentication, HttpServletRequest request) {
        long solutionId = parseId(id);
        if (imageService == null) throw new IllegalStateException("image service is not configured");
        UUID parsedRequestId = parseUuid(requestId);
        int parsedVersion = parseVersion(stateVersion);
        return ApiResponse.ok(imageService.send(solutionId, parsedRequestId, parsedVersion, images,
                () -> owners.findExisting(authentication, request)));
    }

    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern VERSION_TEXT = Pattern.compile("0|[1-9][0-9]{0,9}");

    private static UUID parseUuid(String value) {
        if (value == null || !UUID_TEXT.matcher(value).matches()) throw new ApiException(ErrorCode.INVALID_REQUEST);
        return UUID.fromString(value);
    }

    private static int parseVersion(String value) {
        if (value == null || !VERSION_TEXT.matcher(value).matches()) throw new ApiException(ErrorCode.INVALID_REQUEST);
        long version = Long.parseLong(value);
        if (version >= Integer.MAX_VALUE) throw new ApiException(ErrorCode.INVALID_REQUEST);
        return (int) version;
    }

    /** SOL-04: SOL-03과 같은 CSRF → ID → JSON 본문 → 소유권 순서. 응답 모양도 SOL-03과 같아요. */
    @PatchMapping("/sessions/{id}/facts")
    public ApiResponse<SolutionMessageResponse> updateFacts(@PathVariable("id") String id,
            Authentication authentication, HttpServletRequest request) throws HttpMediaTypeNotSupportedException {
        long solutionId = parseId(id);
        UpdateSolutionFactsRequest input = UpdateSolutionFactsRequest.from(strictBody(request));
        SolutionMessageResponse updated = factsService.update(solutionId, input, () -> owners.findExisting(authentication, request));
        return ApiResponse.ok(replies == null ? updated : replies.withReply(solutionId, updated, null, "correct"));
    }

    /** SOL-05: 본문 없음 또는 빈 JSON 객체만 허용하고 최신 ACTIVE 상태를 종료해요. */
    @PostMapping("/sessions/{id}/complete")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<CompleteSolutionResponse> complete(@PathVariable("id") String id,
            Authentication authentication, HttpServletRequest request) {
        long solutionId = parseId(id);
        byte[] bytes = limitedBody(request);
        if (bytes.length != 0) {
            JsonNode body = parseStrictJson(bytes);
            if (body == null || !body.isObject() || !body.isEmpty()) throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.ok(completeService.complete(solutionId, () -> owners.findExisting(authentication, request)));
    }

    /** 상담 삭제: 대화와 상담 기록을 모두 지워요. 본인 상담만, 없거나 남의 상담이면 404 */
    @org.springframework.web.bind.annotation.DeleteMapping("/sessions/{id}")
    public ApiResponse<java.util.Map<String, Object>> delete(@PathVariable("id") String id,
            Authentication authentication, HttpServletRequest request) {
        long solutionId = parseId(id);
        if (deleteService == null) throw new ApiException(ErrorCode.SOLUTION_NOT_FOUND);
        deleteService.delete(solutionId, () -> owners.findExisting(authentication, request));
        return ApiResponse.ok(java.util.Map.of("solutionId", solutionId, "deleted", true));
    }

    private static byte[] limitedBody(HttpServletRequest request) {
        byte[] bytes;
        try {
            bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (bytes.length > MAX_BODY_BYTES) throw new ApiException(ErrorCode.INVALID_REQUEST);
        return bytes;
    }

    private static JsonNode strictBody(HttpServletRequest request) throws HttpMediaTypeNotSupportedException {
        byte[] bytes = limitedBody(request);
        if (bytes.length == 0) throw new ApiException(ErrorCode.INVALID_REQUEST);
        MediaType type;
        try {
            if (request.getContentType() == null) throw new HttpMediaTypeNotSupportedException("application/json is required");
            type = MediaType.parseMediaType(request.getContentType());
        } catch (InvalidMediaTypeException e) {
            throw new HttpMediaTypeNotSupportedException("application/json is required");
        }
        if (!"application".equals(type.getType()) || !("json".equals(type.getSubtype()) || type.getSubtype().endsWith("+json"))) {
            throw new HttpMediaTypeNotSupportedException(type, java.util.List.of(MediaType.APPLICATION_JSON));
        }
        return parseStrictJson(bytes);
    }

    private static JsonNode parseStrictJson(byte[] bytes) {
        try {
            return STRICT_JSON.readTree(bytes);
        } catch (JacksonException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    private static long parseId(String raw) {
        if (raw == null || !POSITIVE_ID.matcher(raw).matches()) throw new ApiException(ErrorCode.SOLUTION_NOT_FOUND);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.SOLUTION_NOT_FOUND);
        }
    }

    public record CsrfInfo(String headerName, String token) {}
}
