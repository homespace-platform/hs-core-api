package com.hs.contract.service;

import com.hs.contract.dto.signature.CertificateOptionDto;
import com.hs.contract.dto.signature.InitiateSignatureRequest;
import com.hs.contract.dto.signature.SignatureRequestDto;
import com.hs.contract.dto.signature.SignatureStateDto;

import java.util.List;

/**
 * Dịch vụ điều phối toàn bộ luồng ký số VNPT SmartCA cho HomeSpace.
 */
public interface SmartCaSignatureService {

    /** Trả về trạng thái ký của hợp đồng cho người dùng hiện tại. */
    SignatureStateDto getSignatureState(String contractId, String currentUserId);

    /** Trả về danh sách chứng thư SmartCA của người dùng hiện tại. */
    List<CertificateOptionDto> getSigningCertificates(String contractId, String currentUserId);

    /**
     * Khởi tạo yêu cầu ký số (cả chủ nhà và người thuê dùng cùng endpoint).
     * Idempotent: bấm nhiều lần khi request còn hiệu lực trả lại request hiện có.
     */
    SignatureRequestDto initiateSignature(String contractId, String currentUserId, InitiateSignatureRequest request);

    /** Lấy trạng thái một request cụ thể. */
    SignatureRequestDto getSignatureRequest(String contractId, String requestId, String currentUserId);

    /**
     * Poll VNPT và cập nhật trạng thái (gọi từ frontend hoặc scheduler).
     * Nếu SIGNED → chuyển sang PROVIDER_SIGNED (embedding worker sẽ pick up).
     */
    SignatureRequestDto refreshSignatureRequest(String contractId, String requestId, String currentUserId);

    /**
     * Thử lại sau khi bị từ chối/hết hạn/lỗi.
     */
    SignatureRequestDto retrySignature(String contractId, String requestId, String currentUserId);

    /**
     * Gọi từ scheduler: poll VNPT cho một PENDING_USER_CONFIRMATION request.
     */
    void processPendingRequest(String requestId);

    /**
     * Gọi từ embedding scheduler: nhúng signature vào PDF và chuyển SIGNED.
     */
    void embedProviderSignature(String requestId);

    /**
     * Xử lý webhook từ VNPT (chỉ dùng khi webhookEnabled=true).
     */
    void handleWebhook(String tranCode);
}
