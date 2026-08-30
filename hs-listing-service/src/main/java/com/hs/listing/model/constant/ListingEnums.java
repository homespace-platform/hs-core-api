package com.hs.listing.model.constant;

public final class ListingEnums {
    private ListingEnums() {}
    public enum OperatingMode { ALWAYS_OPEN, CUSTOM_SCHEDULE }
    public enum PositionType { GROUND_FLOOR, UPPER_FLOOR, SHOPPING_MALL, OTHER }
    public enum ParkingType { NONE, MOTORBIKE, CAR, MOTORBIKE_AND_CAR }
    public enum RestroomType { PRIVATE, SHARED }
    public enum KitchenType { PRIVATE, SHARED, NONE }
    public enum AccessType { PRIVATE, SHARED }
    public enum AccessHoursType { FLEXIBLE, CURFEW }
    public enum MeterType { PRIVATE, SHARED }
    public enum ParkingPolicy { NONE, FREE, PAID }
    public enum MediaType { IMAGE, VIDEO }
    public enum AddressSourceType { SAVED, NEW }
    public enum ChargeType { ELECTRICITY, WATER, MANAGEMENT, INTERNET, SERVICE_OR_GARBAGE, MOTORBIKE_PARKING, CAR_PARKING, OVERTIME_AIR_CONDITIONING, OTHER }
    public enum BillingMethod { PER_KWH, STATE_WATER_RATE, PER_M3, PER_PERSON_MONTH, PER_MONTH, PER_M2_MONTH, PER_VEHICLE_MONTH, PER_HOUR, FREE, INCLUDED, NOT_APPLICABLE, NEGOTIABLE, CUSTOM }
}
