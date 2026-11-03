package com.hs.contract.service;
import com.hs.contract.dto.billing.*;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentLineItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** A daily agreement credits only rent actually collected; fees and existing invoices stay unchanged. */
@Component @RequiredArgsConstructor
public class FinalRentCalculator {
    private final RentalWorkflowSupport support;
    private final MonthlyInvoiceRepository invoices;
    private final PaymentLineItemRepository lines;
    private final ContractTermsResolver terms;
    public FinalRentCalculation calculate(RentalWorkflowSupport.Source s,LocalDate end,String policy){
        if(end==null||!Set.of("FULL_STARTED_PERIOD","DAILY_PRORATED").contains(policy))throw support.invalid("Chọn ngày kết thúc và cách tính tiền thuê kỳ cuối.");
        LocalDate start=s.rental().getMoveInDate(),periodStart=null,periodEnd=null;int last=-1;
        for(int n=0;n<ContractLeaseCalc.months(s.contract(),s.rental());n++)if(end.isAfter(start.plusMonths(n))&&!end.isAfter(start.plusMonths(n+1L))){periodStart=start.plusMonths(n);periodEnd=start.plusMonths(n+1L);last=n;break;}
        if(periodStart==null)throw support.invalid("Ngày kết thúc nằm ngoài thời gian thuê.");
        var rev=terms.at(s.contract(),periodStart);var financial=support.map(rev.getFinancialSnapshot());
        BigDecimal monthly=financial.get("amountValue")==null?(s.rental().getEffectiveMonthlyRent()==null?s.rental().getMonthlyRentPrice():s.rental().getEffectiveMonthlyRent()):new BigDecimal(financial.get("amountValue").toString());
        if(monthly==null||monthly.signum()<=0)throw support.invalid("Chưa xác định được đơn giá thuê đã thống nhất.");
        long days=ChronoUnit.DAYS.between(periodStart,periodEnd),used=ChronoUnit.DAYS.between(periodStart,end);
        BigDecimal finalRent="DAILY_PRORATED".equals(policy)?monthly.multiply(BigDecimal.valueOf(used)).divide(BigDecimal.valueOf(days),0,RoundingMode.HALF_UP):monthly;
        String sourceId=null;BigDecimal refund=BigDecimal.ZERO;
        if("DAILY_PRORATED".equals(policy)){
            var matching=new ArrayList<com.hs.payment.model.PaymentRequest>();
            var initial=support.payments.findByRentalRequestIdAndType(s.rental().getId(),PaymentType.INITIAL).orElse(null);
            if(initial!=null&&initial.getStatus()==PaymentStatus.CONFIRMED){
                var rent=rent(initial.getId());int count=rent.quantity();
                if(last<count&&rent.price()!=null&&rent.price().compareTo(monthly)==0)matching.add(initial);
            }
            for(var bill:invoices.findByContractIdOrderByPeriodIndexDesc(s.contract().getId())){
                if(bill.isFinalCostSupplement()||bill.getPaymentRequestId()==null||bill.getRentPeriodStart()==null||bill.getRentPeriodEndExclusive()==null)continue;
                if(!periodStart.isBefore(bill.getRentPeriodStart())&&periodStart.isBefore(bill.getRentPeriodEndExclusive()))support.payments.findById(bill.getPaymentRequestId()).filter(p->p.getStatus()==PaymentStatus.CONFIRMED).ifPresent(matching::add);
            }
            if(matching.size()!=1)throw support.invalid("Tính theo ngày cần xác định đúng một nguồn tiền thuê kỳ cuối đã thực nhận. Hãy đối soát/thanh toán hóa đơn gốc trước; hệ thống không sửa hóa đơn hoặc hoàn khoản chưa nhận.");
            var source=matching.getFirst();var rent=rent(source.getId());
            if(rent.price()==null||rent.price().compareTo(monthly)!=0)throw support.invalid("Đơn giá khoản trả trước khác kỳ cuối; cần đối soát trước khi tính hoàn theo ngày.");
            var sourceBill=source.getType()==PaymentType.INITIAL?null:invoices.findByContractIdOrderByPeriodIndexDesc(s.contract().getId()).stream().filter(b->source.getId().equals(b.getPaymentRequestId())).findFirst().orElseThrow();
            LocalDate coverageStart=sourceBill==null?start:sourceBill.getRentPeriodStart();
            // Anchor every boundary on the original lease date: 31 Jan -> 28 Feb -> 31 Mar,
            // never 28 Feb -> 28 Mar. An inconsistent invoice is a reconciliation issue.
            int firstCovered=-1;
            for(int n=0;n<ContractLeaseCalc.months(s.contract(),s.rental());n++)if(start.plusMonths(n).equals(coverageStart)){firstCovered=n;break;}
            if(firstCovered<0||rent.quantity()<1||last<firstCovered||last>=firstCovered+rent.quantity()
                ||sourceBill!=null&&!start.plusMonths(firstCovered+(long)rent.quantity()).equals(sourceBill.getRentPeriodEndExclusive())
                ||rent.total()==null||monthly.multiply(BigDecimal.valueOf(rent.quantity())).compareTo(rent.total())!=0)
                throw support.invalid("Phạm vi hoặc tổng tiền thuê trả trước không khớp kỳ thuê; cần đối soát.");
            int consumed=last-firstCovered;
            refund=monthly.multiply(BigDecimal.valueOf(rent.quantity()-consumed)).subtract(finalRent);
            if(refund.signum()<0||refund.compareTo(rent.total())>0)throw support.invalid("Phạm vi tiền trả trước không khớp kỳ thuê; cần đối soát.");sourceId=source.getId();
        }
        return new FinalRentCalculation(policy,periodStart,periodEnd,end,used,days,monthly,finalRent,refund,sourceId,
            "DAILY_PRORATED".equals(policy)?"Tiền thuê = đơn giá tháng × số ngày thuê / số ngày thực tế của kỳ, làm tròn đến đồng. Hoàn cả phần thuê trả trước chưa sử dụng trong nguồn này. Phí cố định/điện/nước theo hợp đồng và hóa đơn; không tự chia hoặc xóa nợ.":"Tính trọn kỳ đã bắt đầu; khoản hoàn khác chỉ áp dụng khi hai bên ghi riêng và đồng ý.");
    }
    private record Rent(BigDecimal price,int quantity,BigDecimal total){}
    private Rent rent(String id){var rent=lines.findByPaymentRequestIdOrderBySortOrderAsc(id).stream().filter(l->"RENT".equals(l.getType())).toList();if(rent.size()!=1)return new Rent(null,0,BigDecimal.ZERO);var line=rent.getFirst();return new Rent(line.getUnitPrice(),line.getQuantity()==null?0:line.getQuantity(),line.getAmount());}
}
