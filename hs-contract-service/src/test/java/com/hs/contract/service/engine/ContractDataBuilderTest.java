package com.hs.contract.service.engine;

import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingCharge;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import com.hs.user.model.Address;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractDataBuilderTest {

    @Test
    void waterPerPersonUsesOccupantCountAndDoesNotRequireInitialMeter() {
        ListingCharge water = new ListingCharge();
        water.setChargeType(ChargeType.WATER);
        water.setBillingMethod(BillingMethod.PER_PERSON_MONTH);
        water.setAmount(new BigDecimal("100000"));
        water.setCurrency("VND");
        water.setSortOrder(0);

        Listing listing = new Listing();
        listing.setCharges(List.of(water));

        RentalRequest request = RentalRequest.builder()
                .ownerId("landlord-id")
                .renterId("tenant-id")
                .occupantCount(3)
                .build();

        UserRepository userRepository = mock(UserRepository.class);
        AddressRepository addressRepository = mock(AddressRepository.class);
        Address landlordAddress = new Address();
        landlordAddress.setFullAddress("12 Nguyễn Huệ, Phường Sài Gòn, TP. Hồ Chí Minh");
        when(addressRepository.findByUser_IdAndActiveTrue("landlord-id"))
                .thenReturn(Optional.of(landlordAddress));

        ContractDataBuilder builder = new ContractDataBuilder(userRepository, addressRepository);

        ContractDataBuilder.ContractSnapshots snapshots = builder.build(request, listing);

        assertTrue(String.valueOf(snapshots.getCharges().getFirst().get("amountAndMethod"))
                .contains("3 người = 300.000 VNĐ / tháng"));
        assertEquals("300000", snapshots.getCharges().getFirst().get("estimatedMonthlyAmount"));
        assertEquals(
                "Không áp dụng - nước tính theo 3 người (300.000 VNĐ/tháng)",
                snapshots.getMeters().get("waterInitial")
        );
        assertEquals(
                "12 Nguyễn Huệ, Phường Sài Gòn, TP. Hồ Chí Minh",
                snapshots.getLandlord().get("permanentAddress")
        );
    }
}
