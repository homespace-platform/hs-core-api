package com.hs.listing.dto.request;

import com.hs.listing.model.constant.*;
import com.hs.listing.model.constant.ListingEnums.*;
import jakarta.validation.constraints.*;

public record RoomDetailRequest(
        @NotBlank(message = "Mã phòng / tên phòng không được để trống")
        @Size(max = 255, message = "Mã phòng / tên phòng không được vượt quá 255 ký tự")
        String roomCode,
        @Min(0) Integer floorNumber,
        @NotNull RestroomType restroomType,
        KitchenType kitchenType,
        Boolean hasWindow,
        BalconyType balconyType,
        Boolean hasMezzanine,
        @NotNull FurnishingStatus furnishingStatus,
        AccessType accessType,
        AccessHoursType accessHoursType,
        MeterType electricMeterType,
        MeterType waterMeterType,
        @NotNull @Min(1) Integer maxOccupants,
        @Min(0) Integer maxVehicles,
        ParkingPolicy parkingPolicy
) {}
