package com.hs.listing.dto.response;
import java.util.List;
public record ListingPolicyOption(String code,String label,String valueType,String helpText,String unitLabel,
        boolean required,Integer minimumValue,Integer maximumValue,int sortOrder,List<Choice> choices) {
    public record Choice(String value,String label) {}
}
