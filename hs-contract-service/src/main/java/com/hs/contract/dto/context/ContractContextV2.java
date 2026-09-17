package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mô hình dữ liệu ngữ cảnh hợp đồng V2 (ContractContextV2) có kiểu dữ liệu mạnh mẽ,
 * được tổng hợp bất biến từ hồ sơ hai bên, tin đăng và yêu cầu thuê/thanh toán.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractContextV2 {

    @Builder.Default
    private int schemaVersion = 2;

    private Integer revisionNumber;
    private String contractNumber;
    private String signingDate;
    private String signingCity;
    private String specialTerms;

    private ContractPartySnapshot.LandlordParty landlord;
    private ContractPartySnapshot.TenantParty tenant;
    private ContractPropertySnapshot property;
    private ContractLeaseSnapshot lease;
    private ContractFinancialSnapshot financial;
    private ContractInitialPaymentSnapshot initialPayment;

    @Builder.Default
    private List<ContractAmenitySnapshot> amenities = new ArrayList<>();

    @Builder.Default
    private List<ContractChargeSnapshot> charges = new ArrayList<>();

    @Builder.Default
    private List<ContractEquipmentSnapshot> equipments = new ArrayList<>();

    private ContractPolicySnapshot policies;
    private ContractMeterSnapshot meters;
}
