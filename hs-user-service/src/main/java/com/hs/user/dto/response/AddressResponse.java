package com.hs.user.dto.response;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;
@Builder
public record AddressResponse(String id,String refId,String display,String name,String houseNumber,String street,String address,
        String cityId,String city,String districtId,String district,String wardId,String ward,
        BigDecimal latitude,BigDecimal longitude,String addressNote,Boolean active,Instant createdAt,Instant updatedAt) {}
