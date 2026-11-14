package com.hs.contract.service;

import com.hs.contract.dto.billing.*;
import com.hs.contract.dto.complaint.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.contract.repository.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentLineItemRepository;
import com.hs.payment.service.PaymentRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Agreed end date, bilateral physical return, then the existing bill/deposit settlement and receipt workflow. */
@Service @RequiredArgsConstructor
public class MutualTerminationService {
    private final RentalWorkflowSupport support;
    private final ContractRenewalRepository renewals;
    private final ContractAmendmentRepository amendments;
    private final MonthlyInvoiceRepository invoices;
    private final PaymentLineItemRepository lines;
    private final PaymentRequestService payments;
    private final ComplaintCaseService complaints;
    @org.springframework.beans.factory.annotation.Autowired private ContractLifecycleService lifecycle;
    @org.springframework.beans.factory.annotation.Autowired private FinalRentCalculator rentCalculator;
    @org.springframework.beans.factory.annotation.Autowired private com.hs.contract.notification.TerminationNotificationWriter terminationNotices;

    @Transactional public FinalRentCalculation calculate(String rental,String actor,LocalDate end,String policy){var s=support.lock(rental,actor,"EARLY_EXIT");eligible(s);return rentCalculator.calculate(s,end,policy);}

    @Transactional public RentalWorkflowView get(String rental,String actor){return view(support.lock(rental,actor,"EARLY_EXIT"));}
    @Transactional public RentalWorkflowView propose(String rental,String actor,RentalWorkflowPayloads.Termination p){
        var s=support.lock(rental,actor,"EARLY_EXIT");support.version(s,p.expectedRevision());eligible(s);validate(s,p);
        if(Set.of("AGREED","RETURNED").contains(s.record().getStatus()))throw support.invalid("Ngày kết thúc đã được hai bên đồng ý; cần xử lý nghĩa vụ theo thỏa thuận đó.");
        // A counter proposal replaces the reviewed version and clears every old acknowledgement.
        s.record().setCalculationJson(support.json(rentCalculator.calculate(s,p.endExclusive(),p.finalRentPolicy())));
        support.propose(s,p,p.reason(),support.evidence(s,p.evidenceIds()),null);
        s.contract().setMutualTerminationWorkflowId(s.record().getId());support.contracts.save(s.contract());
        terminationNotices.other(s.contract(),actor,com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_RESPONSE_NEEDED);
        return view(s);
    }
    @Transactional public RentalWorkflowView act(String rental,String actor,String action,RentalWorkflowPayloads.Action p){
        return act(rental,actor,action,p,false);
    }
    @Transactional RentalWorkflowView actSigned(String rental,String actor,RentalWorkflowPayloads.Action p){
        return act(rental,actor,"ACCEPT",p,true);
    }
    private RentalWorkflowView act(String rental,String actor,String action,RentalWorkflowPayloads.Action p,boolean signed){
        var s=support.lock(rental,actor,"EARLY_EXIT");support.version(s,p.expectedRevision());var w=s.record();String reason=support.text(p.reason());
        switch(action){
            case "ACCEPT" -> {
                eligible(s);if(!"PROPOSED".equals(w.getStatus()))throw support.invalid("Không có đề xuất chờ đồng ý.");
                support.otherProposer(s);support.acknowledge(p);var proposal=support.payload(s,RentalWorkflowPayloads.Termination.class);validate(s,proposal);
                if(!Objects.equals(w.getSourceRevisionId(),s.contract().getCurrentRevisionId()))throw support.invalid("Hợp đồng đã thay đổi; cần đề xuất lại.");
                s.contract().setAgreedEarlyEndExclusive(proposal.endExclusive());s.contract().setAgreedEarlyBillingMonths(periods(s,proposal.endExclusive()));
                support.contracts.save(s.contract());w.setStatus("AGREED");
                // Only create agreed obligations. Actual transfer/receipt is always a separate action.
                payments.attachOnboardingToContract(rental,s.contract().getId());
                for(var refund:proposal.rentRefunds())payments.createAgreedRentRefundObligation(support.payments.findById(refund.paymentId()).orElseThrow(),refund.amount());
                for(var bill:invoices.findByContractIdOrderByPeriodIndexDesc(s.contract().getId()))
                    if(!bill.isFinalCostSupplement()&&bill.getPeriodIndex()==s.contract().getAgreedEarlyBillingMonths()-1&&bill.getStatus()==MonthlyInvoiceStatus.DRAFT){bill.setPeriodEndExclusive(proposal.endExclusive());invoices.save(bill);}
                if(!signed)terminationNotices.other(s.contract(),actor,com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_AGREED);
            }
            case "REJECT", "WITHDRAW" -> {
                if(!"PROPOSED".equals(w.getStatus()))throw support.invalid("Đề nghị không còn chờ phản hồi.");
                if("REJECT".equals(action))support.otherProposer(s);else if(!actor.equals(w.getProposedBy()))throw support.missing();
                w.setStatus("REJECT".equals(action)?"REJECTED":"WITHDRAWN");s.contract().setMutualTerminationWorkflowId(null);support.contracts.save(s.contract());
                terminationNotices.other(s.contract(),actor,"REJECT".equals(action)
                    ?com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_DECLINED
                    :com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_WITHDRAWN);
            }
            case "CONFIRM_RETURN" -> {
                support.acknowledge(p);
                if(!"AGREED".equals(w.getStatus())||s.contract().getAgreedEarlyEndExclusive()==null||support.time.today().isBefore(s.contract().getAgreedEarlyEndExclusive().minusDays(1)))throw support.invalid("Chưa đến ngày trả nhà hoặc chưa có thỏa thuận hai bên.");
                if(support.owner(s)){if(w.getOwnerConfirmedAt()!=null)throw support.invalid("Bạn đã xác nhận trả nhà.");w.setOwnerConfirmedAt(support.time.now());}
                else {if(w.getTenantConfirmedAt()!=null)throw support.invalid("Bạn đã xác nhận trả nhà.");w.setTenantConfirmedAt(support.time.now());}
                if(w.getOwnerConfirmedAt()!=null&&w.getTenantConfirmedAt()!=null)w.setStatus("RETURNED");
                // No listing/parking release here. Existing final settlement requires actual vacant possession too.
            }
            case "REPORT_ISSUE" -> {
                if(w.getRevision()==0)throw support.invalid("Cần có đề nghị để phản đối; có thể mở khiếu nại chung từ hồ sơ thuê.");
                var complaint=complaints.openFromWorkflow(rental,actor,"EARLY_EXIT",w.getRevision(),reason);w.setComplaintId(complaint.id());
            }
            default -> throw support.invalid("Thao tác không hợp lệ.");
        }
        support.save(s,action,reason,List.of());
        if("CONFIRM_RETURN".equals(action))terminationNotices.other(s.contract(),actor,"RETURNED".equals(w.getStatus())
                ?com.hs.contract.notification.TerminationNotificationWriter.Event.LEASE_RETURN_COMPLETED
                :com.hs.contract.notification.TerminationNotificationWriter.Event.LEASE_RETURN_CONFIRMED);
        return view(s);
    }
    private void eligible(RentalWorkflowSupport.Source s){
        var c=s.contract();if(c.getStatus()!=ContractStatus.ACTIVE||c.getSignedAt()==null||c.getAgreedEarlyEndExclusive()!=null)throw support.invalid("Chỉ đề nghị khi HĐ thuê đang hiệu lực và chưa chốt ngày kết thúc sớm.");
        if(c.getTerminationProposedAt()!=null&&c.getTerminationCancelledAt()==null&&c.getTerminationDeclinedAt()==null)throw support.invalid("Cần xử lý đề nghị chấm dứt do nợ hiện tại trước.");
        if(renewals.findByContractIdOrderByRequestedAtDesc(c.getId()).stream().anyMatch(r->Set.of("REQUESTED","APPROVED").contains(r.getStatus()))
            ||amendments.findByContractIdOrderByCreatedAtDesc(c.getId()).stream().anyMatch(a->ContractAmendmentService.PENDING.contains(a.getStatus())))
            throw support.invalid("Cần xử lý xong gia hạn/phụ lục đang chờ hoặc sắp áp dụng trước khi thỏa thuận chấm dứt.");
        if(support.payments.findAllByContractIdAndType(c.getId(),PaymentType.DEPOSIT_ADJUSTMENT).stream().anyMatch(p->p.getStatus()!=PaymentStatus.CONFIRMED))throw support.invalid("Cần đối soát xong chênh lệch cọc trước.");
    }
    private int periods(RentalWorkflowSupport.Source s,LocalDate end){for(int n=1;n<=ContractLeaseCalc.months(s.contract(),s.rental());n++)if(!s.rental().getMoveInDate().plusMonths(n).isBefore(end))return n;throw support.invalid("Ngày kết thúc không hợp lệ.");}
    private void validate(RentalWorkflowSupport.Source s,RentalWorkflowPayloads.Termination p){
        support.text(p.reason());support.text(p.handoverTerms());
        if(p.endExclusive()==null||p.endExclusive().isBefore(support.time.today())||!p.endExclusive().isAfter(s.rental().getMoveInDate())
            ||!p.endExclusive().isBefore(ContractLeaseCalc.end(s.rental().getMoveInDate(),ContractLeaseCalc.months(s.contract(),s.rental()))))throw support.invalid("Ngày kết thúc phải từ hôm nay, sau ngày bắt đầu và trước hạn thuê gốc.");
        if(!Set.of("FULL_STARTED_PERIOD","DAILY_PRORATED").contains(p.finalRentPolicy())||p.rentRefunds()==null||p.rentRefunds().size()>50)throw support.invalid("Cần chốt cách tính kỳ cuối và khoản hoàn tiền thuê.");
        int last=periods(s,p.endExclusive())-1;
        for(var bill:invoices.findByContractIdOrderByPeriodIndexDesc(s.contract().getId()))
            if(!bill.isFinalCostSupplement()&&(bill.getPeriodIndex()>last||bill.getPeriodIndex()==last&&bill.getStatus()!=MonthlyInvoiceStatus.DRAFT&&bill.getPeriodEndExclusive().isAfter(p.endExclusive())))
                throw support.invalid("Đã có hóa đơn vượt ngày đề nghị. Chọn ngày không cắt hóa đơn đã phát hành; không sửa/xóa chứng từ cũ.");
        var ids=new HashSet<String>();
        for(var refund:p.rentRefunds()){
            if(refund==null||refund.amount()==null||refund.amount().signum()<=0||refund.amount().stripTrailingZeros().scale()>2||refund.amount().compareTo(new BigDecimal("1000000000"))>0||!ids.add(refund.paymentId()))throw support.invalid("Khoản hoàn tiền thuê không hợp lệ hoặc trùng nguồn.");
            var source=support.payments.findById(refund.paymentId()).orElseThrow(support::missing);
            if(!s.rental().getId().equals(source.getRentalRequestId())||source.getStatus()!=PaymentStatus.CONFIRMED||source.getDirection()!=PaymentDirection.TENANT_TO_LANDLORD||!Set.of(PaymentType.INITIAL,PaymentType.MONTHLY_RENT).contains(source.getType()))throw support.invalid("Chỉ hoàn từ khoản tiền thuê thực nhận đúng hồ sơ.");
            BigDecimal rent=lines.findByPaymentRequestIdOrderBySortOrderAsc(source.getId()).stream().filter(l->Set.of("RENT","RENTAL_FEE","MONTHLY_RENT").contains(l.getType())).map(l->l.getAmount()).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(refund.amount().compareTo(rent)>0||support.payments.findByOnboardingKey("REFUND:"+source.getId()).isPresent())throw support.invalid("Khoản hoàn vượt tiền thuê thực nhận hoặc nguồn đã có nghĩa vụ hoàn.");
        }
        if("DAILY_PRORATED".equals(p.finalRentPolicy())){
            var quote=rentCalculator.calculate(s,p.endExclusive(),p.finalRentPolicy());
            if(quote.requiredRefund().signum()>0&&p.rentRefunds().stream().noneMatch(r->quote.sourcePaymentId().equals(r.paymentId())&&r.amount().compareTo(quote.requiredRefund())>=0))throw support.invalid("Danh sách hoàn phải bao gồm đủ khoản thuê trả trước chưa sử dụng theo phép tính ngày và đúng nguồn đã nhận.");
        }
    }
    private RentalWorkflowView view(RentalWorkflowSupport.Source s){
        var w=s.record();var actions=new ArrayList<String>();String blocked=null;
        boolean eligible=true;try{eligible(s);}catch(com.hs.common.advice.entity.AppException e){eligible=false;blocked=e.getMessage();}
        if(eligible){
            actions.add("PROPOSE");if("PROPOSED".equals(w.getStatus())){if(s.actor().equals(w.getProposedBy()))actions.add("WITHDRAW");else actions.addAll(List.of("ACCEPT","REJECT"));}
        }
        if("AGREED".equals(w.getStatus())&&!support.time.today().isBefore(s.contract().getAgreedEarlyEndExclusive().minusDays(1))&&(support.owner(s)?w.getOwnerConfirmedAt()==null:w.getTenantConfirmedAt()==null))actions.add("CONFIRM_RETURN");
        if(!"EMPTY".equals(w.getStatus()))actions.add("REPORT_ISSUE");
        if(s.contract().getAgreedEarlyEndExclusive()!=null)blocked="Thực hiện tiền thuê và khoản hoàn theo đúng thỏa thuận. Sau trả nhà, thanh toán hóa đơn cuối và quyết toán cọc ở mục hiện có.";
        var refunds=support.payments.findByRentalRequestIdAndTypeIn(s.rental().getId(),List.of(PaymentType.INITIAL_PAYMENT_REFUND)).stream().map(p->payments.toResponse(p,s.actor())).toList();
        String obligation="LEASE_CONTINUES";
        if(s.contract().getAgreedEarlyEndExclusive()!=null){
            var current=lifecycle.get(s.contract().getId(),s.actor());var close=current.closure();
            if(close!=null&&"CLOSED".equals(close.getStatus()))obligation="COMPLETED";
            else if(close!=null&&"DISPUTED".equals(close.getStatus()))obligation="DISPUTED";
            else if(refunds.stream().anyMatch(p->p.status()!=PaymentStatus.CONFIRMED))obligation="RENT_REFUND_PENDING";
            else if(!"RETURNED".equals(w.getStatus()))obligation="RETURN_PENDING";
            else if(current.billing()!=null&&!current.billing().allPaid())obligation="BILLS_PENDING";
            else if(close!=null&&Set.of("REFUND_PENDING","BALANCE_PENDING").contains(close.getStatus())){
                var cash="REFUND_PENDING".equals(close.getStatus())?current.refundPayment():current.balancePayment();
                obligation=cash!=null&&cash.status()==PaymentStatus.DISPUTED?"DISPUTED":close.getStatus();
            }else obligation="SETTLEMENT_PENDING";
        }
        var result=support.view(s,actions,blocked,obligation,refunds);
        if(w.getCalculationJson()!=null)try{return result.withRentCalculation(support.mapper.readValue(w.getCalculationJson(),FinalRentCalculation.class));}catch(Exception e){throw new IllegalStateException("Cannot read final-rent agreement",e);}
        return result;
    }
}
