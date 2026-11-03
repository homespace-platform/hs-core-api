package com.hs.contract.service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.payment.model.*;
import java.util.*;

final class ComplaintEvidenceSnapshot {
    private ComplaintEvidenceSnapshot(){}
    static List<String> ids(ObjectMapper mapper,DisputeCase d,List<DisputeCaseEvent> history){
        try {
            var ids=new LinkedHashSet<String>(mapper.readValue(Objects.toString(d.getInitialEvidenceSnapshot(),"[]"),new TypeReference<List<String>>(){}));
            for(var event:history){var map=mapper.readValue(Objects.toString(event.getMetadataJson(),"{}"),new TypeReference<Map<String,Object>>(){});
                var raw=map.get("evidenceIds");if(raw instanceof List<?> list)for(var id:list)if(id instanceof String s)ids.add(s);}
            return List.copyOf(ids);
        }catch(Exception e){throw new IllegalStateException("Cannot read complaint evidence audit",e);}
    }
}
