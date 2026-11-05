package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractRevision;
import com.hs.contract.repository.MonthlyInvoiceRepository;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ParkingReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** Called inside the root draft's rental/contract transaction, before making its new revision. */
@Service @RequiredArgsConstructor
public class ContractDraftResidents {
    private final RentalRequestRepository rentals;
    private final MonthlyInvoiceRepository invoices;
    private final ParkingReservationService parking;
    private final ObjectMapper mapper;

    public void apply(Contract contract, ContractRevision current, UpdateContractRevisionRequest expected) {
        var rental=rentals.findById(contract.getRentalRequestId()).orElseThrow();
        var tenant=expected.getTenant();
        int people=AmendmentTermsBuilder.count(tenant.get("occupantCount"),1,100);
        int bikes=AmendmentTermsBuilder.count(tenant.get("motorbikeCount"),0,100);
        int cars=AmendmentTermsBuilder.count(tenant.get("carCount"),0,100);
        if (people==rental.getOccupantCount() && bikes==Objects.requireNonNullElse(rental.getMotorbikeCount(),0)
                && cars==Objects.requireNonNullElse(rental.getCarCount(),0)) return;
        if (rental.getStatus()!=RentalRequestStatus.ACCEPTED || rental.getListing()==null
                || rental.getListing().getOperationalStatus()!=ListingStatus.RESERVED || rental.getCancellationRequestedAt()!=null
                || rental.getResolutionWorkflowId()!=null || !invoices.findByContractIdOrderByPeriodIndexDesc(contract.getId()).isEmpty())
            throw invalid("Chỉ đổi số người/xe khi hồ sơ giữ chỗ đang ổn định và chưa phát hành hóa đơn.");
        limit(expected.getProperty().get("maxOccupants"),people,"Số người vượt giới hạn hợp đồng.");
        limit(expected.getProperty().get("maxVehicles"),bikes+cars,"Tổng số xe vượt giới hạn hợp đồng.");
        limit(expected.getProperty().get("maxMotorbikeCount"),bikes,"Số xe máy vượt giới hạn hợp đồng.");
        limit(expected.getProperty().get("maxCarCount"),cars,"Số ô tô vượt giới hạn hợp đồng.");
        var live=rental.getListing();
        if(live.getRoomDetail()!=null) {
            limit(live.getRoomDetail().getMaxOccupants(),people,"Số người vượt giới hạn bài đăng đã duyệt.");
            limit(live.getRoomDetail().getMaxVehicles(),bikes+cars,"Số xe vượt giới hạn bài đăng đã duyệt.");
        } else if(live.getHouseDetail()!=null) limit(live.getHouseDetail().getMaxOccupants(),people,"Số người vượt giới hạn bài đăng đã duyệt.");
        else if(live.getApartmentDetail()!=null) limit(live.getApartmentDetail().getMaxOccupants(),people,"Số người vượt giới hạn bài đăng đã duyệt.");
        // Per-person rent was collected before draft creation. It needs an explicit financial agreement,
        // rather than silently rewriting an already confirmed transfer or making first-period billing fail.
        try {
            var source=mapper.readTree(rental.getListingSnapshot()==null?"{}":rental.getListingSnapshot());
            String unit=source.path("priceUnit").asText(rental.getListing().getPriceUnit()==null?"":rental.getListing().getPriceUnit().name());
            if (people!=rental.getOccupantCount() && "PERSON_MONTH".equals(unit))
                throw invalid("Giá thuê tính theo người đã được thu. Thay đổi này cần thỏa thuận tài chính riêng, không tự sửa khoản tháng đầu.");
        } catch (AppException e) {throw e;} catch(Exception e) {throw invalid("Không đọc được đơn giá đã chốt trong hồ sơ thuê.");}
        var terms=new AmendmentTermsBuilder(mapper);
        var charges=terms.draftCharges(expected.getCharges(),tenant,expected.getProperty());
        var initial=expected.getInitialPayment()!=null?expected.getInitialPayment():terms.map(current.getInitialPaymentSnapshot());
        Object paid=initial.get("monthlyCharges");
        BigDecimal prepaidFees=paid instanceof String s && s.matches(".*[^0-9.\\-].*")
                ? decimal(s.replaceAll("[^0-9\\-]", "")) : decimal(paid);
        BigDecimal monthly=charges.stream().filter(r->!Boolean.TRUE.equals(r.get("includedInRent")))
                .map(r->decimal(r.get("estimatedMonthlyAmount"))).reduce(BigDecimal.ZERO,BigDecimal::add);
        if (prepaidFees.signum()>0 && prepaidFees.compareTo(monthly)!=0)
            throw invalid("Hồ sơ cũ đã thu trước phí dịch vụ. Cần đối soát chênh lệch trước khi đổi số người/xe.");
        var costRows=charges.stream().filter(row->row.get("estimatedMonthlyAmount")!=null).map(row->{
            String method=Objects.toString(row.get("billingMethod"),"");
            int quantity="PER_PERSON_MONTH".equals(method)?people:"PER_VEHICLE_MONTH".equals(method)
                    ?("MOTORBIKE_PARKING".equals(row.get("chargeType"))?bikes:cars):1;
            return new com.hs.listing.dto.estimate.PredictableChargeItem(Objects.toString(row.get("chargeType"),"OTHER"),
                    Objects.toString(row.get("name"),"Khoản phí"),method,decimal(row.get("unitAmount")),quantity,
                    decimal(row.get("estimatedMonthlyAmount")),Boolean.TRUE.equals(row.get("includedInRent")),Objects.toString(row.get("note"),""));
        }).toList();
        String costSnapshot;
        try{costSnapshot=mapper.writeValueAsString(costRows);}catch(Exception e){throw invalid("Không cập nhật được bảng phí của hồ sơ thuê.");}
        parking.replaceDraftHoldInBranch(rental, bikes, cars,contract.getBranchId());
        rental.setOccupantCount(people);rental.setMotorbikeCount(bikes);rental.setCarCount(cars);
        rental.setEstimatedMonthlyCharges(monthly);
        rental.setCostBreakdownSnapshot(costSnapshot);
        BigDecimal rent=decimal(expected.getFinancial().get("amountValue"));
        rental.setEstimatedMonthlyTotal(rent.add(monthly));
        rental.setEstimatedLeaseTotal(rent.add(monthly).multiply(BigDecimal.valueOf(rental.getLeaseMonths())).add(rental.getDepositAmount()));
        rentals.save(rental);expected.setCharges(charges);
    }
    private void limit(Object limit,int value,String message) {
        if(limit!=null && !limit.toString().isBlank() && value>AmendmentTermsBuilder.count(limit,0,100))throw invalid(message);
    }
    private BigDecimal decimal(Object value) {
        if(value==null || value.toString().isBlank())return BigDecimal.ZERO;
        try{return new BigDecimal(value.toString());}catch(Exception e){throw invalid("Khoản phí đã chốt không hợp lệ.");}
    }
    private AppException invalid(String message){return new AppException(ContractErrorCode.CONTRACT_DRAFT_INVALID,message);}
}
