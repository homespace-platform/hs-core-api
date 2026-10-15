package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.service.engine.ContractRenderService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.LinkedHashMap;

/** Frozen contract terms; no fee is inferred for contracts signed before this policy existed. */
public record LatePaymentPolicy(Mode mode, BigDecimal amount, int graceDays, BigDecimal cap) {
    public enum Mode { NONE, FIXED_ONCE, FIXED_PER_DAY }

    /** New/unsigned terms: landlord owns only mode + rate; timing and ceiling are system-owned.
     * from() remains a reader for frozen legacy signed policies, including an old lower cap. */
    public static Map<String,Object> forNewAgreement(Map<String,Object> requested, Map<String,Object> previous) {
        var result=new LinkedHashMap<String,Object>(requested==null?Map.of():requested);
        var old=previous==null?Map.<String,Object>of():previous;
        try {
            Mode mode=Mode.valueOf(String.valueOf(result.getOrDefault("latePaymentFeeMode","NONE")));
            BigDecimal amount=mode==Mode.NONE?BigDecimal.ZERO:new BigDecimal(String.valueOf(result.get("latePaymentFeeAmount")));
            BigDecimal cap=mode==Mode.FIXED_PER_DAY?amount.multiply(BigDecimal.valueOf(MonthlyBillingSchedule.AUTO_ROLLOVER_DAYS)):amount;
            for(String key:java.util.List.of("latePaymentFeeGraceDays","latePaymentFeeCap","billingRules")) {
                Object expected=key.equals("latePaymentFeeGraceDays")?MonthlyBillingSchedule.LATE_FEE_GRACE_DAYS:key.equals("latePaymentFeeCap")?cap:MonthlyBillingSchedule.rules();
                // A full snapshot may echo its previous values; it cannot choose new workflow controls.
                if(result.containsKey(key) && !equivalent(result.get(key),old.get(key)) && !equivalent(result.get(key),expected))
                    throw new IllegalArgumentException("System billing controls are read-only");
            }
            result.put("latePaymentFeeMode",mode.name());result.put("latePaymentFeeAmount",amount.toPlainString());
            result.put("latePaymentFeeGraceDays",MonthlyBillingSchedule.LATE_FEE_GRACE_DAYS);result.put("latePaymentFeeCap",cap.toPlainString());
            result.put("billingRules",MonthlyBillingSchedule.rules());
            from(result);return result;
        } catch(RuntimeException ex) { throw new AppException(ContractErrorCode.CONTRACT_LATE_FEE_INVALID,
                "Chỉ được chọn cách tính và mức phí phạt. Hạn thanh toán, ngày ân hạn và mức phạt tối đa do hệ thống tính."); }
    }
    private static boolean equivalent(Object a,Object b) {
        if(a==null||b==null)return a==b;
        if(a instanceof Map<?,?> || b instanceof Map<?,?>)return a.equals(b);
        try { return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0; }
        catch(NumberFormatException ex){return a.equals(b);}
    }

    public static LatePaymentPolicy from(Map<String, Object> policies) {
        if (policies == null || policies.get("latePaymentFeeMode") == null)
            return new LatePaymentPolicy(Mode.NONE, BigDecimal.ZERO, 0, null);
        try {
            Mode mode = Mode.valueOf(String.valueOf(policies.get("latePaymentFeeMode")));
            if (mode == Mode.NONE) return new LatePaymentPolicy(mode, BigDecimal.ZERO, 0, null);
            BigDecimal amount = new BigDecimal(String.valueOf(policies.get("latePaymentFeeAmount")));
            int grace = Integer.parseInt(String.valueOf(policies.get("latePaymentFeeGraceDays")));
            Object capValue = policies.get("latePaymentFeeCap");
            BigDecimal cap = capValue == null || String.valueOf(capValue).isBlank()
                    ? null : new BigDecimal(String.valueOf(capValue));
            if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("1000000000")) > 0
                    || amount.stripTrailingZeros().scale() > 0 || grace < 0 || grace > 30
                    || (cap != null && (cap.compareTo(amount) < 0
                    || cap.compareTo(new BigDecimal("10000000000")) > 0
                    || cap.stripTrailingZeros().scale() > 0)))
                throw new IllegalArgumentException("Invalid late fee");
            return new LatePaymentPolicy(mode, amount, grace, cap);
        } catch (RuntimeException ex) {
            throw new AppException(ContractErrorCode.CONTRACT_LATE_FEE_INVALID);
        }
    }

    public BigDecimal accrued(Instant dueAt, Instant asOf, ZoneId zone) {
        long chargeableDays = chargedDays(dueAt, asOf, zone);
        if (chargeableDays == 0) return BigDecimal.ZERO;
        BigDecimal fee = mode == Mode.FIXED_ONCE ? amount : amount.multiply(BigDecimal.valueOf(chargeableDays));
        return cap == null ? fee : fee.min(cap);
    }

    public long chargedDays(Instant dueAt, Instant asOf, ZoneId zone) {
        if (mode == Mode.NONE || dueAt == null || asOf == null || !asOf.isAfter(dueAt)) return 0;
        LocalDate dueDate = dueAt.atZone(zone).toLocalDate();
        LocalDate currentDate = asOf.atZone(zone).toLocalDate();
        long chargeableDays = Math.max(0, ChronoUnit.DAYS.between(dueDate, currentDate) - graceDays);
        return Math.min(chargeableDays, MonthlyBillingSchedule.AUTO_ROLLOVER_DAYS);
    }

    public String clause() {
        if (mode == Mode.NONE) return "";
        String price = ContractRenderService.formatVND(amount);
        String timing = graceDays == 0 ? "từ ngày đầu tiên sau hạn thanh toán"
                : "sau " + graceDays + " ngày kể từ hạn thanh toán";
        String rule = mode == Mode.FIXED_ONCE ? "một lần " + price
                : price + " cho mỗi ngày chậm trả, tối đa " + MonthlyBillingSchedule.AUTO_ROLLOVER_DAYS + " ngày tính phạt trên mỗi hóa đơn";
        return "Phí chậm thanh toán: Bên B trả thêm " + rule + " " + timing
                + (cap == null ? "" : ", tổng phí tối đa " + ContractRenderService.formatVND(cap))
                + ". Chỉ tính trên hóa đơn chưa thanh toán và thể hiện riêng trên hóa đơn. Hệ thống tạm ngừng cập nhật phí khi Bên B báo đã chuyển khoản để đối soát; nếu chứng từ bị từ chối, phí được tính lại theo số ngày chậm trả và các giới hạn nêu trên.";
    }
}
