package com.hs.contract.service;

import com.hs.contract.dto.billing.*;
import com.hs.contract.dto.complaint.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.contract.model.MonthlyInvoice;
import com.hs.contract.repository.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentLineItemRepository;
import com.hs.payment.service.PaymentRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    @org.springframework.beans.factory.annotation.Autowired private ContractTermsResolver effectiveTerms;
    @org.springframework.beans.factory.annotation.Autowired private WorkflowSigningDocumentRepository signingDocuments;
    @org.springframework.beans.factory.annotation.Autowired private WorkflowDocumentService workflowDocuments;
    @org.springframework.beans.factory.annotation.Autowired private FinalRentCalculator rentCalculator;
    @org.springframework.beans.factory.annotation.Autowired private com.hs.contract.notification.TerminationNotificationWriter terminationNotices;

    @Transactional public FinalRentCalculation calculate(String rental,String actor,LocalDate end,String policy){
        var s=support.lock(rental,actor,"EARLY_EXIT");eligible(s);
        if(!"FULL_STARTED_PERIOD".equals(policy))throw support.invalid("Chấm dứt sớm chỉ áp dụng khi hết trọn kỳ thuê.");
        validateEnd(s,end,coverage(s));return rentCalculator.calculate(s,end,"FULL_STARTED_PERIOD");
    }

    @Transactional public RentalWorkflowView get(String rental,String actor){return view(support.lock(rental,actor,"EARLY_EXIT"));}
    @Transactional public RentalWorkflowView propose(String rental,String actor,RentalWorkflowPayloads.Termination p){
        var s=support.lock(rental,actor,"EARLY_EXIT");
        if(support.owner(s))throw support.invalid("Người thuê gửi đề nghị; chủ nhà đồng ý hoặc từ chối.");
        if(p==null)throw support.invalid("Thiếu nội dung đề nghị.");
        support.version(s,p.expectedRevision());eligible(s);p=normalize(p);validate(s,p);
        if(Set.of("APPROVED","AGREED","RETURNED").contains(s.record().getStatus()))throw support.invalid("Ngày kết thúc đã được hai bên đồng ý; cần xử lý nghĩa vụ theo thỏa thuận đó.");
        // A counter proposal replaces the reviewed version and clears every old acknowledgement.
        s.record().setCalculationJson(support.json(rentCalculator.calculate(s,p.endExclusive(),p.finalRentPolicy())));
        support.propose(s,p,p.reason(),support.evidence(s,p.evidenceIds()),null);
        s.record().setSourceRevisionId(effectiveTerms.at(s.contract(),support.time.today()).getId());support.records.saveAndFlush(s.record());
        s.contract().setMutualTerminationWorkflowId(s.record().getId());support.contracts.save(s.contract());
        terminationNotices.other(s.contract(),actor,com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_RESPONSE_NEEDED);
        return view(s);
    }
    @Transactional public RentalWorkflowView act(String rental,String actor,String action,RentalWorkflowPayloads.Action p){
        return act(rental,actor,action,p,false);
    }
    @Transactional RentalWorkflowView actSigned(String rental,String actor,RentalWorkflowPayloads.Action p){
        return act(rental,actor,"APPLY_SIGNED",p,true);
    }
    private RentalWorkflowView act(String rental,String actor,String action,RentalWorkflowPayloads.Action p,boolean signed){
        var s=support.lock(rental,actor,"EARLY_EXIT");support.version(s,p.expectedRevision());var w=s.record();String reason=support.text(p.reason());
        switch(action){
            case "ACCEPT" -> {
                eligible(s);if(!"PROPOSED".equals(w.getStatus()))throw support.invalid("Không có đề xuất chờ đồng ý.");
                if(!support.owner(s)||!s.rental().getRenterId().equals(w.getProposedBy()))throw support.invalid("Chỉ chủ nhà đồng ý đề nghị do người thuê gửi. Đề nghị cũ cần rút và lập lại.");
                support.otherProposer(s);support.acknowledge(p);var proposal=support.payload(s,RentalWorkflowPayloads.Termination.class);validate(s,proposal);
                requireCurrentSource(s);
                w.setStatus("APPROVED");w.setRespondBy(null);
            }
            case "APPLY_SIGNED" -> {
                if(!signed||!"APPROVED".equals(w.getStatus()))throw support.invalid("Cần đủ hai chữ ký trên phụ lục đã được chủ nhà đồng ý.");
                eligible(s);requireCurrentSource(s);
                var proposal=support.payload(s,RentalWorkflowPayloads.Termination.class);validate(s,proposal);
                s.contract().setAgreedEarlyEndExclusive(proposal.endExclusive());s.contract().setAgreedEarlyBillingMonths(periods(s,proposal.endExclusive()));
                support.contracts.save(s.contract());w.setStatus("AGREED");
                // A whole-period approval changes only the operational end, never existing invoices or receipts.
                payments.attachOnboardingToContract(rental,s.contract().getId());
                if(!signed)terminationNotices.other(s.contract(),actor,com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_AGREED);
            }
            case "REJECT", "WITHDRAW", "CANCEL" -> {
                if(!Set.of("PROPOSED","APPROVED").contains(w.getStatus()))throw support.invalid("Đề nghị không còn chờ phản hồi.");
                if("APPROVED".equals(w.getStatus()))requireNotFullySigned(s);
                if("CANCEL".equals(action)){if(!"APPROVED".equals(w.getStatus()))throw support.invalid("Chỉ hủy phụ lục đang chờ ký.");}
                else if("REJECT".equals(action))support.otherProposer(s);else if(!actor.equals(w.getProposedBy()))throw support.missing();
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
        if("ACCEPT".equals(action)) {
            workflowDocuments.prepare(rental,"early-exit",actor,w.getRevision());
            terminationNotices.other(s.contract(),actor,com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_APPROVED);
        }
        if("CONFIRM_RETURN".equals(action))terminationNotices.other(s.contract(),actor,"RETURNED".equals(w.getStatus())
                ?com.hs.contract.notification.TerminationNotificationWriter.Event.LEASE_RETURN_COMPLETED
                :com.hs.contract.notification.TerminationNotificationWriter.Event.LEASE_RETURN_CONFIRMED);
        return view(s);
    }
    private void requireCurrentSource(RentalWorkflowSupport.Source s){
        if(!Objects.equals(s.record().getSourceRevisionId(),effectiveTerms.at(s.contract(),support.time.today()).getId()))throw support.invalid("Điều khoản đang áp dụng đã đổi; cần lập lại phụ lục.");
    }
    private void requireNotFullySigned(RentalWorkflowSupport.Source s){
        for(var binding:signingDocuments.findByWorkflowIdOrderByPreparedAtDesc(s.record().getId())){
            var agreement=support.contracts.findById(binding.getSigningContractId()).orElseThrow(support::missing);
            if(agreement.getSignedAt()!=null||agreement.getStatus()==ContractStatus.ACTIVE)
                throw support.invalid("Hai bên đã ký đủ phụ lục; không thể hủy đề nghị.");
        }
    }
    private void eligible(RentalWorkflowSupport.Source s){
        var c=s.contract();if(c.getStatus()!=ContractStatus.ACTIVE||c.getSignedAt()==null||c.getAgreedEarlyEndExclusive()!=null)throw support.invalid("Chỉ đề nghị khi HĐ thuê đang hiệu lực và chưa chốt ngày kết thúc sớm.");
        if(c.getTerminationProposedAt()!=null&&c.getTerminationCancelledAt()==null&&c.getTerminationDeclinedAt()==null)throw support.invalid("Cần xử lý đề nghị chấm dứt do nợ hiện tại trước.");
        if(renewals.findByContractIdOrderByRequestedAtDesc(c.getId()).stream().anyMatch(r->Set.of("REQUESTED","APPROVED").contains(r.getStatus()))
            ||amendments.findByContractIdOrderByCreatedAtDesc(c.getId()).stream().anyMatch(a->ContractAmendmentService.PENDING.contains(a.getStatus())))
            throw support.invalid("Cần xử lý xong gia hạn/phụ lục đang chờ hoặc sắp áp dụng trước khi thỏa thuận chấm dứt.");
        if(support.payments.findAllByContractIdAndType(c.getId(),PaymentType.DEPOSIT_ADJUSTMENT).stream().anyMatch(p->p.getStatus()!=PaymentStatus.CONFIRMED))throw support.invalid("Cần đối soát xong chênh lệch cọc trước.");
    }
    private int periods(RentalWorkflowSupport.Source s,LocalDate end){
        for(int n=1;n<ContractLeaseCalc.months(s.contract(),s.rental());n++)if(s.rental().getMoveInDate().plusMonths(n).equals(end))return n;
        throw support.invalid("Chỉ chọn ngày ở cuối kỳ thuê, tính từ ngày bắt đầu hợp đồng và trước hạn thuê hiện tại.");
    }
    private RentalWorkflowPayloads.Termination normalize(RentalWorkflowPayloads.Termination p){
        if(p.finalRentPolicy()!=null&&!"FULL_STARTED_PERIOD".equals(p.finalRentPolicy())||p.rentRefunds()!=null&&!p.rentRefunds().isEmpty())
            throw support.invalid("Đề nghị mới chỉ kết thúc trọn kỳ thuê; không tính theo ngày hoặc sửa khoản hoàn tiền.");
        return new RentalWorkflowPayloads.Termination(p.expectedRevision(),p.endExclusive(),support.text(p.reason()),
            "Hai bên xác nhận trả và nhận lại nhà theo luồng bàn giao hiện có.","FULL_STARTED_PERIOD",List.of(),p.evidenceIds());
    }
    private record Coverage(List<MonthlyInvoice> bills,int initialMonths) {}
    private Coverage coverage(RentalWorkflowSupport.Source s){
        int initialMonths=0;var bills=invoices.findByContractIdOrderByPeriodIndexDesc(s.contract().getId());
        for(var source:support.payments.findByRentalRequestIdAndTypeIn(s.rental().getId(),List.of(PaymentType.MONTHLY_RENT))){
            if(Set.of(PaymentStatus.CANCELLED,PaymentStatus.EXPIRED).contains(source.getStatus()))continue;
            if(bills.stream().noneMatch(b->Objects.equals(source.getId(),b.getPaymentRequestId())||source.getInvoiceId()!=null&&Objects.equals(source.getInvoiceId(),b.getId())))
                throw support.invalid("Khoản tiền thuê chưa gắn với hóa đơn/kỳ xác định; cần đối soát trước khi chấm dứt sớm.");
        }
        var initial=support.payments.findByRentalRequestIdAndType(s.rental().getId(),PaymentType.INITIAL).orElse(null);
        if(initial!=null&&!Set.of(PaymentStatus.CANCELLED,PaymentStatus.EXPIRED).contains(initial.getStatus())){
            var sourceLines=lines.findByPaymentRequestIdOrderBySortOrderAsc(initial.getId());
            var rent=sourceLines.stream()
                .filter(l->Set.of("RENT","RENTAL_FEE","MONTHLY_RENT").contains(l.getType())).toList();
            if(rent.size()>1||!rent.isEmpty()&&(rent.getFirst().getQuantity()==null||rent.getFirst().getQuantity()<1))
                throw support.invalid("Phạm vi tiền thuê đầu kỳ chưa rõ; cần đối soát trước khi chấm dứt sớm.");
            if(rent.isEmpty()&&(initial.getTotalAmount()==null||sourceLines.stream().anyMatch(l->l.getAmount()==null)
                ||sourceLines.stream().map(com.hs.payment.model.PaymentLineItem::getAmount).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add).compareTo(initial.getTotalAmount())!=0))
                throw support.invalid("Chưa xác định được phần tiền thuê đầu kỳ; cần đối soát nguồn tiền trước.");
            if(!rent.isEmpty())initialMonths=rent.getFirst().getQuantity();
        }
        return new Coverage(bills,initialMonths);
    }
    private void validateEnd(RentalWorkflowSupport.Source s,LocalDate end,Coverage coverage){
        if(end==null||!end.isAfter(support.time.today()))throw support.invalid("Ngày ở cuối cùng phải từ hôm nay trở đi.");
        int count=periods(s,end),last=count-1;
        if(coverage.initialMonths()>count)throw support.invalid("Tiền thuê đầu kỳ đã bao phủ sau ngày đề nghị; cần chọn kỳ không cắt khoản đã lập hoặc đã thu.");
        for(var bill:coverage.bills())if(!bill.isFinalCostSupplement()){
            if(bill.getPeriodIndex()>last||bill.getPeriodEndExclusive()!=null&&bill.getPeriodEndExclusive().isAfter(end)
                ||bill.getStatus()!=MonthlyInvoiceStatus.DRAFT&&bill.getRentPeriodEndExclusive()!=null&&bill.getRentPeriodEndExclusive().isAfter(end))
                throw support.invalid("Đã có hóa đơn hoặc tiền thuê bao phủ sau ngày đề nghị. Chọn ngày không cắt khoản đã lập; không sửa/xóa chứng từ cũ.");
        }
    }
    private void validate(RentalWorkflowSupport.Source s,RentalWorkflowPayloads.Termination p){
        support.text(p.reason());
        if(!"FULL_STARTED_PERIOD".equals(p.finalRentPolicy())||p.rentRefunds()==null||!p.rentRefunds().isEmpty())
            throw support.invalid("Đề nghị cũ không áp dụng trọn kỳ thuê; cần rút và gửi lại ngày hợp lệ.");
        validateEnd(s,p.endExclusive(),coverage(s));
    }
    private List<RentalWorkflowView.Choice> choices(RentalWorkflowSupport.Source s){
        var coverage=coverage(s);var result=new ArrayList<RentalWorkflowView.Choice>();
        for(int n=1;n<ContractLeaseCalc.months(s.contract(),s.rental());n++){
            LocalDate end=s.rental().getMoveInDate().plusMonths(n);
            try{validateEnd(s,end,coverage);result.add(new RentalWorkflowView.Choice(end.minusDays(1),end));}
            catch(com.hs.common.advice.entity.AppException ignored){ /* Only offer boundaries that can be proposed now. */ }
        }
        return List.copyOf(result);
    }
    private RentalWorkflowView view(RentalWorkflowSupport.Source s){
        var w=s.record();var actions=new ArrayList<String>();String blocked=null;
        boolean eligible=true;try{eligible(s);}catch(com.hs.common.advice.entity.AppException e){eligible=false;blocked=e.getMessage();}
        List<RentalWorkflowView.Choice> choices=List.of();
        if(eligible){try{choices=choices(s);}catch(com.hs.common.advice.entity.AppException e){blocked=e.getMessage();}}
        if(eligible&&choices.isEmpty()&&blocked==null)blocked="Không còn kỳ thuê trọn vẹn có thể kết thúc sớm mà không cắt hóa đơn hoặc khoản thuê đã lập.";
        if(eligible&&!"APPROVED".equals(w.getStatus())&&!choices.isEmpty()&&!support.owner(s))actions.add("PROPOSE");
        if("APPROVED".equals(w.getStatus()))actions.add("CANCEL");
        else if("PROPOSED".equals(w.getStatus())){
            // An expired or blocked proposal can still be withdrawn/rejected, never silently agreed.
            if(s.actor().equals(w.getProposedBy()))actions.add("WITHDRAW");
            else {if("PROPOSED".equals(w.getStatus())&&eligible&&support.owner(s)&&s.rental().getRenterId().equals(w.getProposedBy())){
                try{validate(s,support.payload(s,RentalWorkflowPayloads.Termination.class));actions.add("ACCEPT");}
                catch(com.hs.common.advice.entity.AppException e){blocked=e.getMessage();}
            }actions.add("REJECT");}
        }
        if("APPROVED".equals(w.getStatus()))blocked="Chủ nhà đã đồng ý. Hạn thuê hiện tại giữ nguyên cho đến khi đủ hai chữ ký hợp lệ trên phụ lục.";
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
        LocalDate proposedLastDay=null;
        if(w.getPayloadJson()!=null){var proposal=support.payload(s,RentalWorkflowPayloads.Termination.class);if(proposal.endExclusive()!=null)proposedLastDay=proposal.endExclusive().minusDays(1);}
        LocalDate currentEnd=s.contract().getAgreedEarlyEndExclusive()!=null?s.contract().getAgreedEarlyEndExclusive():ContractLeaseCalc.end(s.rental().getMoveInDate(),ContractLeaseCalc.months(s.contract(),s.rental()));
        var result=support.view(s,actions,blocked,obligation,refunds).withTerminationOptions(new RentalWorkflowView.TerminationOptions(currentEnd,proposedLastDay,choices));
        if(w.getCalculationJson()!=null)try{return result.withRentCalculation(support.mapper.readValue(w.getCalculationJson(),FinalRentCalculation.class));}catch(Exception e){throw new IllegalStateException("Cannot read final-rent agreement",e);}
        return result;
    }
}
