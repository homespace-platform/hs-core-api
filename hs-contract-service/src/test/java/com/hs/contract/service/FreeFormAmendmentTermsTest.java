package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.engine.*;
import com.hs.contract.dto.amendment.AmendmentComparison;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreeFormAmendmentTermsTest {
    final ObjectMapper mapper=new ObjectMapper();
    final AmendmentTermsBuilder builder=new AmendmentTermsBuilder(mapper);
    ContractRevision original;
    @BeforeEach void setup(){
        original=ContractRevision.builder().id("signed-original").landlordSnapshot(builder.write(Map.of("fullName","Owner","idNumber","123","phone","0901")))
                .tenantSnapshot(builder.write(Map.of("fullName","Tenant","idNumber","456","occupantCount",2,"motorbikeCount",0,"carCount",0)))
                .propertySnapshot(builder.write(Map.of("maxOccupants",4,"areaText","50 m²","fullAddress","Original house","propertyType","Nhà nguyên căn")))
                .leaseSnapshot(builder.write(Map.of("startDateText","15/10/2026","endDateText","14/01/2027","durationMonths",3)))
                .financialSnapshot(builder.write(Map.of("amountValue","12400000","depositAmountValue","24800000","depositDescription","Original conditions")))
                .chargesSnapshot("[]").equipmentSnapshot("[]").amenitiesSnapshot("[]")
                .policiesSnapshot(builder.write(Map.of("latePaymentFeeMode","NONE","depositRefundDays",15)))
                .initialMetersSnapshot(builder.write(Map.of("electricityInitial",1500,"waterInitial",85)))
                .initialPaymentSnapshot(builder.write(Map.of("status","CONFIRMED","totalAmount",37200000))).specialTerms("Original agreement").build();
    }
    Map<String,Object> changes(String key,Object value){var c=new LinkedHashMap<String,Object>();c.put("freeForm",true);c.put(key,value);return c;}
    @Test void inheritedListingVehicleLimitsDoNotRejectNegotiatedCounts() {
        original.setPropertySnapshot(builder.write(Map.of("maxVehicles",0,"maxMotorbikeCount",0,"maxCarCount",0)));
        var next=builder.build(original,Map.of("freeForm",true,"motorbikeCount",5,"carCount",2));
        assertEquals(5,builder.object(next.terms().get("tenant")).get("motorbikeCount"));
        assertEquals(2,builder.object(next.terms().get("tenant")).get("carCount"));
        assertEquals(2,next.comparison().size());
        assertThrows(AppException.class,()->builder.build(original,Map.of("freeForm",true,"motorbikeCount",-1)));
        assertThrows(AppException.class,()->builder.build(original,Map.of("freeForm",true,"carCount","1.5")));
        assertThrows(AppException.class,()->builder.build(original,Map.of("freeForm",true,"carCount",101)));
        assertThrows(AppException.class,()->builder.build(original,Map.of("freeForm",true,"motorbikeCount",5,"fields",Map.of("property",Map.of("maxVehicles",4)))));
    }
    @Test void bothPartiesBankFieldsSurviveAmendmentAndWordPopulationForAllCategories() throws Exception {
        for(String type:List.of("Nhà nguyên căn","Căn hộ chung cư","Phòng trọ")) {
            var a=Map.of("bankName","Ngân hàng A mới","bankAccountNumber","000123456","bankAccountHolder","NGUYEN VAN A");
            var t=Map.of("bankName","Ngân hàng B mới","bankAccountNumber","000987654","bankAccountHolder","NGUYEN VAN B");
            var result=builder.build(original,changes("fields",Map.of("landlord",a,"tenant",t,"property",Map.of("propertyType",type,"unitNumber","Căn 01"))));
            var terms=result.terms();
            var render=new ContractRenderService(mapper);
            @SuppressWarnings("unchecked") var model=render.buildDataModelFromSnapshots((Map)terms.get("landlord"),(Map)terms.get("tenant"),(Map)terms.get("property"),(Map)terms.get("lease"),(Map)terms.get("financial"),(List)terms.get("charges"),(List)terms.get("equipments"),(Map)terms.get("meters"),(Map)terms.get("initialPayment"),(List)terms.get("amenities"),(Map)terms.get("policies"),String.valueOf(terms.get("specialTerms")),3,2,"PL-TEST",LocalDate.of(2026,10,15),"HCM");
            for(String key:a.keySet()) {
                assertEquals(a.get(key),ContractRenderService.resolvePath(model,"landlord."+key));
                assertEquals(t.get(key),ContractRenderService.resolvePath(model,"tenant."+key));
                assertTrue(result.comparison().stream().anyMatch(r->r.field().equals("landlord."+key)));
                assertTrue(result.comparison().stream().anyMatch(r->r.field().equals("tenant."+key)));
            }
            assertEquals(builder.map(original.getInitialPaymentSnapshot()),terms.get("initialPayment"));
        }
    }
    Map<String,Object> charge(String name,String method,String rate){return Map.of("name",name,"billingMethod",method,"unitAmount",rate,"chargeType","OTHER","note","Manual agreement");}
    @Test void freelyEnteredDescriptionsAndContactDataAreDiffedWithoutChangingSignedSource(){
        String source=original.getPropertySnapshot();
        var result=builder.build(original,changes("fields",Map.of("property",Map.of("fullAddress","Corrected address","floor","Sân thượng"),"landlord",Map.of("phone","0902"))));
        assertEquals("Corrected address",((Map<?,?>)result.terms().get("property")).get("fullAddress"));
        assertEquals(3,result.comparison().size());assertEquals(source,original.getPropertySnapshot());
        assertEquals("123",((Map<?,?>)result.terms().get("landlord")).get("idNumber"));
    }
    @Test void realAccountIdsSettledHistoryAndMeterHistoryCannotBeInjected(){
        for(var fields:List.of(Map.of("landlord",Map.of("userId","Other signer")),Map.of("tenant",Map.of("accountId","999")),
                Map.of("financial",Map.of("paidAt","0")),Map.of("initialPayment",Map.of("status","UNPAID")),Map.of("meters",Map.of("electricityInitial",0))))
            assertThrows(AppException.class,()->builder.build(original,changes("fields",fields)));
        assertThrows(AppException.class,()->builder.build(original,changes("contractId","someone-else")));
    }
    @Test void arbitraryEquipmentAndAmenitiesCanBeAddedRenamedAndRemovedWithoutListingCatalog(){
        var c=changes("equipments",List.of(Map.of("name","Đàn piano nhập tay","quantity","1","condition","Theo biên bản mới","note","Không có trên Listing")));
        c.put("amenities",List.of(Map.of("name","Phòng đọc sách")));
        var first=builder.build(original,c);assertEquals(2,first.comparison().size());
        assertEquals("AGREED_AMENDMENT",((List<Map<String,Object>>)first.terms().get("amenities")).getFirst().get("sourceType"));
        var copy=ContractRevision.builder().equipmentSnapshot(builder.write(first.terms().get("equipments"))).amenitiesSnapshot(builder.write(first.terms().get("amenities"))).build();
        assertEquals(List.of(),builder.build(copy,changes("equipments",List.of())).terms().get("equipments"));
    }
    @Test void handTypedChargesCalculateAmountsAndDoNotDoubleCurrencyUnits(){
        original.setChargesSnapshot(builder.write(List.of(Map.of("name","Điện","billingMethod","PER_KWH","unitAmount","3000","chargeType","ELECTRICITY","includedInRent",false,"amountAndMethod","3.000 VNĐ / kWh"))));
        var c=changes("charges",List.of(charge("Phí đọc sách","Người / tháng","10000"),charge("Phí diện tích","m² / tháng","1000"),charge("Điện","kWh","3500")));
        c.put("rentAmount","12000000");c.put("occupantCount","3");
        var result=builder.build(original,c);var rows=(List<Map<String,Object>>)result.terms().get("charges");
        assertEquals("30000",rows.get(0).get("estimatedMonthlyAmount"));assertEquals("50000",rows.get(1).get("estimatedMonthlyAmount"));
        assertEquals("ELECTRICITY",rows.get(2).get("chargeType"));assertNull(rows.get(2).get("estimatedMonthlyAmount"));
        assertFalse(((Map<?,?>)result.terms().get("financial")).get("amountNumber").toString().contains("VNĐ VNĐ"));
        assertFalse(rows.get(0).get("amountAndMethod").toString().contains("VNĐ đ"));
    }
    Map<String,Object> storedFee(String name,String type,String method,String rate,boolean included,String total) {
        var row=new LinkedHashMap<String,Object>();row.put("name",name);row.put("chargeType",type);row.put("billingMethod",method);row.put("unitAmount",rate);
        row.put("includedInRent",included);row.put("estimatedMonthlyAmount",total);row.put("note","");
        row.put("amountAndMethod",included?"Đã bao gồm trong giá thuê":rate+" VNĐ / "+method);return row;
    }
    @Test void vehicleEditWithIncludedParkingDoesNotInventAnyFeeChangeInEitherPayloadMode(){
        var parking=storedFee("Phí gửi xe máy","MOTORBIKE_PARKING","PER_VEHICLE_MONTH","0",true,"0");
        var electric=storedFee("Tiền điện","ELECTRICITY","PER_KWH","3175",false,null);
        var water=storedFee("Tiền nước","WATER","PER_M3","15750",false,null);
        original.setChargesSnapshot(builder.write(List.of(parking,electric,water)));
        for(boolean free:List.of(false,true)) {
            var c=new LinkedHashMap<String,Object>();if(free)c.put("freeForm",true);c.put("motorbikeCount",2);c.put("carCount",1);
            var result=builder.build(original,c);
            assertFalse(result.comparison().stream().anyMatch(r->r.field().startsWith("charges")));
            assertEquals(List.of(parking,electric,water),result.terms().get("charges"));
        }
    }
    @Test void paidParkingRecalculationComparesOnlyAffectedFeeAndPreservesMeters(){
        var parking=storedFee("Phí xe máy","MOTORBIKE_PARKING","PER_VEHICLE_MONTH","50000",false,"0");
        var electric=storedFee("Tiền điện","ELECTRICITY","PER_KWH","3175",false,null);
        var water=storedFee("Tiền nước","WATER","PER_M3","15750",false,null);
        original.setChargesSnapshot(builder.write(List.of(parking,electric,water)));
        for(boolean free:List.of(false,true)) {
            var c=new LinkedHashMap<String,Object>();if(free)c.put("freeForm",true);c.put("motorbikeCount",2);
            var result=builder.build(original,c);var fees=result.comparison().stream().filter(r->r.field().startsWith("charges.")).toList();
            assertEquals(1,fees.size());assertEquals("Khoản phí · Phí xe máy",fees.getFirst().label());
            assertTrue(fees.getFirst().after().contains("100000"));assertFalse(fees.getFirst().after().contains("Tiền điện"));
            var rows=builder.rows(builder.write(result.terms().get("charges")));assertEquals(electric,rows.get(1));assertEquals(water,rows.get(2));
        }
    }
    @Test void feeComparisonIncludesOnlyEditedAddedOrRemovedRowsAndIgnoresFormattingOnly(){
        var wifi=storedFee("WiFi","INTERNET","PER_MONTH","150000",false,"150000");
        var rubbish=storedFee("Rác","SERVICE_OR_GARBAGE","PER_PERSON_MONTH","30000",false,"60000");
        original.setChargesSnapshot(builder.write(List.of(wifi,rubbish)));
        var edited=new LinkedHashMap<>(wifi);edited.put("unitAmount","160000");
        var result=builder.build(original,changes("charges",List.of(edited,rubbish)));
        assertEquals(1,result.comparison().size());assertEquals("Khoản phí · WiFi",result.comparison().getFirst().label());
        var formatted=new LinkedHashMap<>(wifi);formatted.put("unitAmount","150000.00");formatted.put("amountAndMethod","150.000 đ/tháng");
        assertThrows(AppException.class,()->builder.build(original,changes("charges",List.of(formatted,rubbish))));
        var removed=builder.build(original,changes("charges",List.of(wifi)));assertEquals(1,removed.comparison().size());assertEquals("Không có",removed.comparison().getFirst().after());
        var added=builder.build(original,changes("charges",List.of(wifi,rubbish,charge("Phí mới","Tháng","10000"))));
        assertEquals(1,added.comparison().size());assertEquals("Không có",added.comparison().getFirst().before());
    }
    @Test void changingPeopleStillShowsRealPerPersonFeeChangeOnly(){
        var rubbish=storedFee("Rác","SERVICE_OR_GARBAGE","PER_PERSON_MONTH","30000",false,"60000");
        var wifi=storedFee("WiFi","INTERNET","PER_MONTH","150000",false,"150000");
        original.setChargesSnapshot(builder.write(List.of(rubbish,wifi)));
        var result=builder.build(original,changes("occupantCount",3));
        var fees=result.comparison().stream().filter(r->r.field().startsWith("charges.")).toList();
        assertEquals(1,fees.size());assertEquals("Khoản phí · Rác",fees.getFirst().label());assertTrue(fees.getFirst().after().contains("90000"));
    }
    @Test void financialChangesCannotApplyImmediatelyButHandWrittenNonFinancialTermsCan(){
        var c=changes("fields",Map.of("policies",Map.of("latePaymentFeeMode","Mỗi ngày","latePaymentFeeAmount","100000")));
        c.put("applyImmediately",true);assertThrows(AppException.class,()->builder.build(original,c));
        var nonFinancial=changes("specialTerms","A freely written new agreement");nonFinancial.put("applyImmediately",true);assertEquals(1,builder.build(original,nonFinancial).comparison().size());
    }
    @Test void validationRejectsInvalidNumbersDuplicateRowsMissingRatesAndMissingInitialMeter(){
        for(String amount:List.of("-1","1.5","1000000001",""))assertThrows(AppException.class,()->builder.build(original,changes("rentAmount",amount)));
        assertThrows(AppException.class,()->builder.build(original,changes("charges",List.of(charge("X","Tháng","10"),charge("x","Tháng","20")))));
        assertThrows(AppException.class,()->builder.build(original,changes("charges",List.of(charge("X","một lúc nào đó","10")))));
        original.setInitialMetersSnapshot("{}");assertThrows(AppException.class,()->builder.build(original,changes("charges",List.of(charge("Meter","kWh","1000")))));
    }
    @Test void meteredServiceCannotBeRemovedOrConvertedAndAnUnrelatedEditKeepsLegacyRowsIntact(){
        var meter=Map.<String,Object>of("name","Điện","billingMethod","PER_KWH","unitAmount","3000","chargeType","ELECTRICITY","includedInRent",false,"amountAndMethod","3.000 VNĐ / kWh");
        var legacy=Map.<String,Object>of("name","Nước giá nhà nước","billingMethod","STATE_WATER_RATE","chargeType","WATER","amountAndMethod","Theo hóa đơn nhà cung cấp");
        original.setChargesSnapshot(builder.write(List.of(meter,legacy)));
        assertThrows(AppException.class,()->builder.build(original,changes("charges",List.of(legacy))));
        var converted=new LinkedHashMap<>(meter);converted.put("billingMethod","Tháng");
        assertThrows(AppException.class,()->builder.build(original,changes("charges",List.of(converted,legacy))));
        var result=builder.build(original,changes("fields",Map.of("property",Map.of("floor","Tầng 2"))));
        assertEquals(legacy,((List<Map<String,Object>>)result.terms().get("charges")).get(1));
    }
    @Test void policyTextAndDeadlineAreFreeFromListingChoicesButOperationalDaysStayNumeric(){
        var c=changes("policyRows",List.of(Map.of("code","petPolicy","label","Quy định vật nuôi","text","Chỉ nuôi mèo đã đăng ký, tối đa 2 con"),Map.of("code","depositRefundDays","label","Hoàn cọc","text","7")));
        var result=builder.build(original,c);var p=(Map<?,?>)result.terms().get("policies");assertEquals(7,p.get("depositRefundDays"));assertNull(p.get("petsAllowed"));
        var row=((List<Map<String,Object>>)p.get("listingPolicies")).getFirst();assertEquals("TEXT",row.get("valueType"));assertTrue(row.get("displayValue").toString().contains("2 con"));
        assertThrows(AppException.class,()->builder.build(original,changes("policyRows",List.of(Map.of("code","depositRefundDays","label","Cọc","text","tùy ý")))));
    }
    @Test void fullTemplateReceivesNewScalarAndTableDataThenGetsEffectiveDateAppendix() throws Exception {
        var property=builder.map(original.getPropertySnapshot());property.put("features",List.of(Map.of("featureName","Ban công riêng","featureValue","10 mét vuông")));original.setPropertySnapshot(builder.write(property));
        var c=changes("rentAmount","12000000");c.put("equipments",List.of(Map.of("name","Bàn mới nhập tay","quantity","1","condition","Mới","note","Thỏa thuận")));
        c.put("featureRows",List.of(Map.of("featureName","Ban công riêng","featureValue","12 m²")));
        c.put("policyRows",List.of(Map.of("code","quietHoursPolicy","label","Giờ yên tĩnh","text","Sau 23:00"),Map.of("code","depositRefundDays","label","Thời hạn hoàn cọc","text","15")));
        var t=builder.build(original,c).terms();var renderer=new ContractRenderService(mapper);
        var model=renderer.buildDataModelFromSnapshots((Map)t.get("landlord"),(Map)t.get("tenant"),(Map)t.get("property"),(Map)t.get("lease"),(Map)t.get("financial"),(List)t.get("charges"),(List)t.get("equipments"),(Map)t.get("meters"),(Map)t.get("initialPayment"),(List)t.get("amenities"),(Map)t.get("policies"),String.valueOf(t.get("specialTerms")),3,1,"ROOT-PL-NEW",LocalDate.of(2026,10,15),"HCM");
        byte[] template;try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            for(String marker:List.of("{{property.fullAddress}}","{{rent.amountNumber}}","{{#equipmentTable}}","{{#policiesTable}}","{{#propertyFeaturesTable}}"))doc.createParagraph().createRun().setText(marker);
            doc.write(out);template=out.toByteArray();
        }
        var agreements=mock(ContractRepository.class);var proposals=mock(ContractAmendmentRepository.class);var service=mock(ContractAmendmentService.class);
        var agreement=Contract.builder().id("new").contractNumber("ROOT-PL-NEW").build();
        when(proposals.findByAgreementContractId("new")).thenReturn(Optional.of(ContractAmendment.builder().contractId("root").effectiveOn(LocalDate.of(2026,11,15)).reason("Thỏa thuận mới").build()));
        when(agreements.findById("root")).thenReturn(Optional.of(Contract.builder().contractNumber("ROOT").build()));
        when(service.comparison(any())).thenReturn(List.of(new AmendmentComparison("rentAmount","Giá thuê","12400000","12000000")));
        var appendix=new ContractAmendmentRenderer(agreements,proposals,service,builder);
        byte[] rendered=appendix.appendAdjustment(renderer.renderDocx(new ByteArrayInputStream(template),model),agreement);
        try(var doc=new XWPFDocument(new ByteArrayInputStream(rendered))) {
            var extractor=new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc);String text=extractor.getText();
            assertTrue(text.contains("12.000.000 VNĐ/tháng"));assertTrue(text.contains("Bàn mới nhập tay"));assertTrue(text.contains("Sau 23:00"));assertTrue(text.contains("Ban công riêng"));
            assertTrue(text.contains("15/11/2026"));assertTrue(text.contains("ROOT"));assertFalse(text.contains("{{"));assertFalse(text.contains("BÊN A — CHỦ NHÀ                         BÊN B"));
        }
        assertEquals("Original agreement",original.getSpecialTerms());
    }
    @Test void documentIdentityBankLeaseAndDepositCanBeEditedButSignedSourceStaysIntact() {
        var c=changes("fields",Map.of("landlord",Map.of("fullName","Tên trên phụ lục","idNumber","001234","bankAccountNumber","000111"),
                "lease",Map.of("handoverDateText","16/10/2026","durationMonths","6"),
                "financial",Map.of("depositAmountValue","20000000","paymentCycle","Mỗi 3 tháng","paymentMethod","Chuyển khoản theo thỏa thuận")));
        var result=builder.build(original,c);var lease=(Map<?,?>)result.terms().get("lease");var f=(Map<?,?>)result.terms().get("financial");
        assertEquals("14/04/2027",lease.get("endDateText"));assertEquals(6,lease.get("durationMonths"));
        assertEquals(3,f.get("paymentCycleMonths"));assertEquals("20.000.000 VNĐ",f.get("depositAmountNumber"));
        assertEquals("000111",((Map<?,?>)result.terms().get("landlord")).get("bankAccountNumber"));
        assertEquals("Owner",builder.map(original.getLandlordSnapshot()).get("fullName"));
        assertEquals("24800000",builder.map(original.getFinancialSnapshot()).get("depositAmountValue"));
        assertThrows(AppException.class,()->builder.build(original,changes("fields",Map.of("lease",Map.of("handoverDateText","31/02/2026")))));
        assertThrows(AppException.class,()->builder.build(original,changes("fields",Map.of("financial",Map.of("paymentCycle","tùy lúc")))));
        assertThrows(AppException.class,()->builder.build(original,changes("fields",Map.of("property",Map.of("maxVehicles","4a")))));
    }
    @Test void fixedPropertyFeatureSchemaRejectsRemovalRenameReorderAndNonNumericCounts() {
        var p=builder.map(original.getPropertySnapshot());
        var rows=List.of(Map.of("featureName","Số phòng ngủ","featureValue","2 phòng"),Map.of("featureName","Diện tích đất","featureValue","50 m²"));
        p.put("features",rows);original.setPropertySnapshot(builder.write(p));
        for(var invalid:List.of(List.of(),List.of(rows.getFirst()),List.of(rows.getLast(),rows.getFirst()),
                List.of(Map.of("featureName","Tên mới","featureValue","2"),rows.getLast()),List.of(Map.of("featureName","Số phòng ngủ","featureValue","4a phòng"),rows.getLast())))
            assertThrows(AppException.class,()->builder.build(original,changes("featureRows",invalid)));
        var result=builder.build(original,changes("featureRows",List.of(Map.of("featureName","Số phòng ngủ","featureValue","4 phòng"),Map.of("featureName","Diện tích đất","featureValue","55.5 mét vuông"))));
        assertEquals(2,((List<?>)((Map<?,?>)result.terms().get("property")).get("features")).size());
        assertEquals("50 m²",rows.getLast().get("featureValue"));
    }
    @Test void squareMetresWordsRemainNumericForAreaCharges() {
        var c=changes("fields",Map.of("property",Map.of("areaText","75.5 mét vuông")));
        c.put("charges",List.of(charge("Phí diện tích","mét vuông / tháng","1000")));
        var result=builder.build(original,c);assertEquals("75.5",((Map<?,?>)result.terms().get("property")).get("areaM2"));
        assertEquals("75500",((List<Map<String,Object>>)result.terms().get("charges")).getFirst().get("estimatedMonthlyAmount"));
    }
    @Test void freeVehicleFeesUseTheExplicitVehicleNotAnInferredNameAndRejectContradictoryFreePrices(){
        var c=changes("charges",List.of(charge("Phí thỏa thuận A","Ô tô/tháng","100000"),charge("Phí thỏa thuận B","Xe máy / tháng","20000")));
        c.put("motorbikeCount",2);c.put("carCount",1);
        var rows=(List<Map<String,Object>>)builder.build(original,c).terms().get("charges");
        assertEquals("100000",rows.get(0).get("estimatedMonthlyAmount"));assertEquals("40000",rows.get(1).get("estimatedMonthlyAmount"));
        assertThrows(AppException.class,()->builder.build(original,changes("charges",List.of(charge("Parking","Xe / tháng","100000")))));
        assertThrows(AppException.class,()->builder.build(original,changes("charges",List.of(charge("Free fee","Miễn phí","100000")))));
    }
    @Test void capacityMirrorsCannotConflictAndProspectiveDueAndPenaltyRulesAreCanonical(){
        var p=builder.map(original.getPropertySnapshot());p.put("features",List.of(Map.of("featureName","Số người tối đa","featureValue","4 người")));original.setPropertySnapshot(builder.write(p));
        var c=changes("fields",Map.of("property",Map.of("maxOccupants",6),
                "policies",Map.of("latePaymentFeeMode","Mỗi ngày","latePaymentFeeAmount","100000")));
        var result=builder.build(original,c).terms();var features=(List<Map<String,Object>>)((Map<?,?>)result.get("property")).get("features");
        assertEquals("6 người",features.getFirst().get("featureValue"));
        var policy=(Map)result.get("policies");assertEquals(0,policy.get("latePaymentFeeGraceDays"));assertEquals("1000000",policy.get("latePaymentFeeCap"));
        var fee=LatePaymentPolicy.from((Map)result.get("policies"));assertEquals(LatePaymentPolicy.Mode.FIXED_PER_DAY,fee.mode());assertEquals(0,fee.amount().compareTo(new java.math.BigDecimal("100000")));
        assertThrows(AppException.class,()->builder.build(original,changes("featureRows",List.of(Map.of("featureName","Số người tối đa","featureValue","99 người")))));
    }
    @Test void systemMilestonesAndPenaltyCeilingCannotBeChangedThroughEitherFieldsOrPolicyRows() {
        for(var injected:List.of(Map.of("financial",Map.of("paymentDueOffsetDays",8)),Map.of("financial",Map.of("paymentDueDay","Tùy chủ nhà")),
                Map.of("policies",Map.of("latePaymentFeeGraceDays",7)),Map.of("policies",Map.of("latePaymentFeeCap","9999999")),Map.of("policies",Map.of("billingRules",Map.of("maximumPenaltyDays",30)))))
            assertThrows(AppException.class,()->builder.build(original,changes("fields",injected)));
        for(String key:List.of("paymentDueOffsetDays","latePaymentFeeCap","billingRules","maximumPenaltyDays"))
            assertThrows(AppException.class,()->builder.build(original,changes("policyRows",List.of(Map.of("code",key,"label","Giới hạn tự chọn","text","30")))));
        assertThrows(AppException.class,()->builder.build(original,changes("systemTerms","Xóa quy trình")));
    }
    @Test void operationalClauseIsReadOnlyMetadataAndRestoredInTheNewSigningDocument() {
        original.setSpecialTerms(ContractDataBuilder.DEFAULT_OVERDUE_TERMS+"\nGhi chú của hai bên");
        var snapshot=builder.snapshot(original);
        assertEquals("Ghi chú của hai bên",snapshot.get("specialTerms"));assertEquals(ContractDataBuilder.DEFAULT_OVERDUE_TERMS,snapshot.get("systemTerms"));
        var result=builder.build(original,changes("specialTerms","Ghi chú mới"));
        String documentTerms=builder.documentSpecialTerms(result.terms());
        assertTrue(documentTerms.contains(ContractDataBuilder.DEFAULT_OVERDUE_TERMS));assertTrue(documentTerms.endsWith("Ghi chú mới"));
        assertTrue(original.getSpecialTerms().endsWith("Ghi chú của hai bên"));
    }
}
