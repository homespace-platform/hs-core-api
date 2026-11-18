package com.hs.api.controller.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hs.common.dto.ApiResponse;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.service.KycService;
import com.hs.user.service.kyc.VnptKycStore;
import com.hs.user.service.kyc.VnptKycProxy;
import com.hs.user.utils.CurrentUserUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController @RequiredArgsConstructor
@RequestMapping("/users/me/kyc/vnpt")
public class VnptKycController {
    private final VnptKycStore store;
    private final VnptKycProxy proxy;
    private final KycService kyc;
    private final CurrentUserUtils user;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.hs.user.config.vnpt.VnptKycProperties config;
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    @PostMapping("/session")
    public ApiResponse<VnptKycStore.Session> start() {
        return ApiResponse.<VnptKycStore.Session>builder().result(store.start(user.getCurrentUser().getId())).build();
    }
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    @PostMapping(value="/sessions/{id}/sdk/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@PathVariable String id,@RequestPart("file") MultipartFile file) throws IOException {
        if(file.getSize()>5*1048576L)throw new com.hs.common.advice.entity.AppException(com.hs.user.advice.entity.enums.UserErrorCode.KYC_INPUT_INVALID);
        return sdkResponse(proxy.upload(user.getCurrentUser().getId(),id,file.getBytes(),file.getContentType()));
    }
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    @PostMapping(value="/sessions/{id}/sdk/{step}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> step(@PathVariable String id,@PathVariable String step,jakarta.servlet.http.HttpServletRequest request) throws IOException {
        String userId = user.getCurrentUser().getId();
        int limit = Math.max(1, Math.min(5, config.maxImageMb())) * 3 * 1048576 + 65536;
        if (request.getContentLengthLong() > limit) throw new com.hs.common.advice.entity.AppException(com.hs.user.advice.entity.enums.UserErrorCode.KYC_INPUT_INVALID);
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw new com.hs.common.advice.entity.AppException(com.hs.user.advice.entity.enums.UserErrorCode.KYC_INPUT_INVALID);
        JsonNode input;
        try { input = mapper.readTree(bytes); }
        catch (IOException ex) { throw new com.hs.common.advice.entity.AppException(com.hs.user.advice.entity.enums.UserErrorCode.KYC_INPUT_INVALID); }
        return sdkResponse(proxy.process(userId,id,step,input));
    }
    private Map<String,Object> sdkResponse(JsonNode result) {
        return mapper.convertValue(result,new TypeReference<>() {});
    }
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    @PostMapping("/sessions/{id}/finalize")
    public ApiResponse<KycStatusResponse> finish(@PathVariable String id) {
        String userId=user.getCurrentUser().getId();store.finalizeAttempt(userId,id);
        return ApiResponse.<KycStatusResponse>builder().result(kyc.getCurrentStatus(userId)).build();
    }
}
