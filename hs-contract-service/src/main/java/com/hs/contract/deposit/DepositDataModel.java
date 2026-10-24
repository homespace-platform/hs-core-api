package com.hs.contract.deposit;

import com.deepoove.poi.data.*;
import com.hs.contract.service.engine.ContractRenderService;
import java.util.*;

/** Tables are materialized after reading immutable JSON, never serialized as poi-tl objects. */
public final class DepositDataModel {
    private DepositDataModel() {}
    @SuppressWarnings("unchecked")
    public static Map<String,Object> render(Map<String,Object> frozen) {
        var model=new LinkedHashMap<>(frozen);
        var rows=new ArrayList<RowRenderData>();
        Object source=model.remove("depositOptionalRows");
        if(source instanceof List<?> list) for(Object item:list) {
            var row=(Map<String,Object>)item;
            if(!Objects.toString(row.get("value"),"").isBlank())rows.add(Rows.of(Objects.toString(row.get("label"),""),row.get("value").toString()).create());
        }
        if(rows.isEmpty())model.put("depositOptionalInfoTable","");
        else {rows.addFirst(Rows.of("Thông tin bổ sung","Giá trị ghi nhận").bgColor("F2F4F7").textBold().create());model.put("depositOptionalInfoTable",Tables.create(rows.toArray(new RowRenderData[0])));}
        return model;
    }
    public static List<String> missing(Map<String,Object> model,DepositFieldCatalog catalog,com.hs.listing.model.constant.ListingCategory category) {
        return catalog.required(category).stream().map(f->f.getKey()).filter(k->!k.startsWith("#"))
            .filter(k->{Object v=ContractRenderService.resolvePath(model,k);return v==null||v.toString().isBlank();}).toList();
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> dummy(Map<String,Object> lease) {
        var model=new LinkedHashMap<>(lease);
        var catalog=new DepositFieldCatalog(new com.hs.contract.service.engine.ContractFieldCatalog());
        for(var field:catalog.fields()) {
            if(field.getKey().startsWith("#"))continue;
            String[] path=field.getKey().split("\\.");
            var group=new LinkedHashMap<String,Object>((Map<String,Object>)model.getOrDefault(path[0],Map.of()));
            if(!group.containsKey(path[1]) || Objects.toString(group.get(path[1]),"").isBlank())group.put(path[1],field.getExample());
            model.put(path[0],group);
        }
        model.put("depositOptionalRows",List.of(Map.of("label","Dữ liệu xem thử","value","Minh họa, không phải giao dịch thật")));
        return render(model);
    }
}
