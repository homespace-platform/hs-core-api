package com.hs.listing.dto.response;
public record ListingPolicyResponse(String code,String label,String valueType,String value,String note,
        String displayValue,String unitLabel,boolean required,Integer minimumValue,Integer maximumValue,int sortOrder) {}
