package com.hs.listing.model.constant;

public enum ListingSubtype {
    APARTMENT_STANDARD(ListingCategory.APARTMENT), APARTMENT_STUDIO(ListingCategory.APARTMENT), APARTMENT_DUPLEX(ListingCategory.APARTMENT), APARTMENT_PENTHOUSE(ListingCategory.APARTMENT), APARTMENT_OFFICETEL(ListingCategory.APARTMENT), APARTMENT_OTHER(ListingCategory.APARTMENT),
    HOUSE_TOWNHOUSE(ListingCategory.HOUSE), HOUSE_ALLEY(ListingCategory.HOUSE), HOUSE_VILLA(ListingCategory.HOUSE), HOUSE_GRADE_4(ListingCategory.HOUSE), HOUSE_OTHER(ListingCategory.HOUSE),
    OFFICE_TRADITIONAL(ListingCategory.OFFICE), OFFICE_SERVICED(ListingCategory.OFFICE), OFFICE_COWORKING(ListingCategory.OFFICE), OFFICE_SHARED(ListingCategory.OFFICE), OFFICE_OTHER(ListingCategory.OFFICE),
    COMMERCIAL_STORE(ListingCategory.COMMERCIAL_SPACE), COMMERCIAL_KIOSK(ListingCategory.COMMERCIAL_SPACE), COMMERCIAL_SHOWROOM(ListingCategory.COMMERCIAL_SPACE), COMMERCIAL_SHOPHOUSE(ListingCategory.COMMERCIAL_SPACE), COMMERCIAL_MALL(ListingCategory.COMMERCIAL_SPACE), COMMERCIAL_OTHER(ListingCategory.COMMERCIAL_SPACE),
    ROOM_BOARDING(ListingCategory.ROOM), ROOM_IN_HOUSE(ListingCategory.ROOM), ROOM_SERVICED_APARTMENT(ListingCategory.ROOM), ROOM_DORMITORY(ListingCategory.ROOM), ROOM_OTHER(ListingCategory.ROOM);
    private final ListingCategory category;
    ListingSubtype(ListingCategory category) { this.category = category; }
    public ListingCategory category() { return category; }
}
