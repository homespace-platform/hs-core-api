package com.hs.listing.dto.request; import jakarta.validation.constraints.NotNull; import java.time.*;
public record OfficeOperatingHourRequest(@NotNull DayOfWeek dayOfWeek,@NotNull LocalTime openTime,@NotNull LocalTime closeTime) {}
