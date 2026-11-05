package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;
import com.hs.contract.model.*;
import com.hs.contract.repository.MonthlyInvoiceRepository;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ParkingReservationService;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractDraftResidentsTest {
    final RentalRequestRepository rentals=mock(RentalRequestRepository.class);
    final MonthlyInvoiceRepository invoices=mock(MonthlyInvoiceRepository.class);
    final ParkingReservationService parking=mock(ParkingReservationService.class);
    final ObjectMapper mapper=new ObjectMapper();
    final ContractDraftResidents service=new ContractDraftResidents(rentals,invoices,parking,mapper);
    final Contract contract=Contract.builder().id("contract").rentalRequestId("rental").build();
    final RentalRequest rental=RentalRequest.builder().id("rental").status(RentalRequestStatus.ACCEPTED)
            .listing(Listing.builder().id("listing").status(ListingStatus.RESERVED).build())
            .occupantCount(2).motorbikeCount(1).carCount(0).moveInDate(LocalDate.of(2027,1,31)).leaseMonths(3)
            .depositAmount(new BigDecimal("3000000")).listingSnapshot("{\"priceUnit\":\"MONTH\"}").costBreakdownSnapshot("historical estimate").build();
    final ContractRevision current=ContractRevision.builder().initialPaymentSnapshot("{\"monthlyCharges\":\"0 VNĐ\",\"totalAmount\":\"6.000.000 VNĐ\"}").build();
    UpdateContractRevisionRequest request;
    @BeforeEach void setup() {
        when(rentals.findById("rental")).thenReturn(Optional.of(rental));
        request=UpdateContractRevisionRequest.builder().tenant(new LinkedHashMap<>(Map.of("occupantCount",3,"motorbikeCount",2,"carCount",1)))
                .property(Map.of("maxOccupants",4,"maxVehicles",3)).financial(Map.of("amountValue","3000000"))
                .charges(List.of(row("Nước","WATER","PER_PERSON_MONTH","10000","20000"),
                        row("Xe máy","MOTORBIKE_PARKING","PER_VEHICLE_MONTH","50000","50000"),
                        row("Ô tô","CAR_PARKING","PER_VEHICLE_MONTH","100000","0"),
                        row("WiFi","INTERNET","PER_MONTH","100000","100000")))
                .build();
    }
    Map<String,Object> row(String name,String type,String method,String unit,String estimated) {
        return Map.of("name",name,"chargeType",type,"billingMethod",method,"unitAmount",unit,"estimatedMonthlyAmount",estimated,"includedInRent",false);
    }
    @Test void calculatesPersonAndBothVehicleFeesAndUpdatesOnlyUnsignedAllocations() {
        service.apply(contract,current,request);
        assertEquals(3,rental.getOccupantCount());assertEquals(2,rental.getMotorbikeCount());assertEquals(1,rental.getCarCount());
        assertEquals(new BigDecimal("330000"),rental.getEstimatedMonthlyCharges());
        assertEquals(new BigDecimal("3330000"),rental.getEstimatedMonthlyTotal());assertEquals(new BigDecimal("12990000"),rental.getEstimatedLeaseTotal());
        assertEquals(List.of("30000","100000","100000","100000"),request.getCharges().stream().map(r->r.get("estimatedMonthlyAmount")).toList());
        assertEquals(new BigDecimal("3000000"),rental.getDepositAmount());
        var cost=new AmendmentTermsBuilder(mapper).rows(rental.getCostBreakdownSnapshot());
        assertEquals(3,cost.getFirst().get("quantity"));assertEquals(30000,cost.getFirst().get("amount"));
        assertEquals(2,cost.get(1).get("quantity"));assertEquals(100000,cost.get(1).get("amount"));
        assertEquals("{\"priceUnit\":\"MONTH\"}",rental.getListingSnapshot());
        assertTrue(current.getInitialPaymentSnapshot().contains("6.000.000"));
        service.apply(contract,current,request);verify(parking,times(1)).replaceDraftHoldInBranch(rental,2,1,null);
    }
    @Test void rejectsInvalidCountsAndCapacityAndIssuedBillsBeforeWriting() {
        for (String key:List.of("occupantCount","motorbikeCount","carCount")) {
            Object old=request.getTenant().put(key,-1);
            assertThrows(AppException.class,()->service.apply(contract,current,request));request.getTenant().put(key,old);
        }
        request.setProperty(Map.of("maxOccupants",2));assertThrows(AppException.class,()->service.apply(contract,current,request));
        request.setProperty(Map.of("maxVehicles",2));assertThrows(AppException.class,()->service.apply(contract,current,request));
        request.setProperty(Map.of("maxOccupants",4,"maxVehicles",3));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(MonthlyInvoice.builder().build()));
        assertThrows(AppException.class,()->service.apply(contract,current,request));
        assertEquals(2,rental.getOccupantCount());verifyNoInteractions(parking);verify(rentals,never()).save(any());
    }
    @Test void refusesSilentlyChangingPaidPerPersonRentOrPrepaidLegacyFees() {
        rental.setListingSnapshot("{\"priceUnit\":\"PERSON_MONTH\"}");assertThrows(AppException.class,()->service.apply(contract,current,request));
        rental.setListingSnapshot("{\"priceUnit\":\"MONTH\"}");current.setInitialPaymentSnapshot("{\"monthlyCharges\":\"170.000 VNĐ\"}");
        assertThrows(AppException.class,()->service.apply(contract,current,request));
        verifyNoInteractions(parking);verify(rentals,never()).save(any());
    }
    @Test void poolCapacityFailureKeepsRentalCountsAndEstimatesUnchanged() {
        doThrow(new IllegalStateException("pool full")).when(parking).replaceDraftHoldInBranch(rental,2,1,null);
        assertThrows(IllegalStateException.class,()->service.apply(contract,current,request));
        assertEquals(2,rental.getOccupantCount());assertNull(rental.getEstimatedMonthlyCharges());verify(rentals,never()).save(any());
    }
}
