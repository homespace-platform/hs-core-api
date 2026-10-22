package com.hs.common.location;

import com.fasterxml.jackson.databind.*;
import com.hs.common.advice.entity.AppException;
import com.hs.common.persistence.AddressFields;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Server-side v4 adapter. Never accepts an upstream URL and never logs the API key/request URI. */
@Service
public class VietmapService {
    private static final String NEW_ADDRESS_DISPLAY_TYPE="1";
    public record Suggestion(String refId,String display,String name,String address) {}
    public record Place(String refId,String display,String name,String houseNumber,String street,String address,
                        String cityId,String city,String districtId,String district,String wardId,String ward,
                        BigDecimal latitude,BigDecimal longitude) {}
    @FunctionalInterface interface Transport { JsonNode get(String path,Map<String,String> params) throws Exception; }
    private final Transport transport;
    private final Semaphore inflight=new Semaphore(8);
    private long window=System.currentTimeMillis();
    private int requests;

    @org.springframework.beans.factory.annotation.Autowired
    public VietmapService(@Value("${vietmap.api-key:}") String key) {
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
        ObjectMapper mapper=new ObjectMapper();
        transport=(path,params) -> {
            if(key==null || key.isBlank()) throw new AppException(2451,"Chưa cấu hình API key Vietmap trên backend.",HttpStatus.SERVICE_UNAVAILABLE);
            StringBuilder query=new StringBuilder("apikey=").append(encode(key));
            params.forEach((k,v) -> query.append('&').append(k).append('=').append(encode(v)));
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://maps.vietmap.vn"+path+"?"+query))
                    .timeout(Duration.ofSeconds(8)).header("Accept","application/json").GET().build();
            HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream body=response.body()) {
                if(response.statusCode()!=200) throw unavailable();
                byte[] bytes=body.readNBytes(2_000_001);
                if(bytes.length>2_000_000) throw unavailable();
                return mapper.readTree(bytes);
            }
        };
    }
    VietmapService(Transport transport) { this.transport=transport; }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private synchronized void quota() {
        long now=System.currentTimeMillis(); if(now-window>=60_000) {window=now;requests=0;}
        if(++requests>300) throw new AppException(2452,"Đang có nhiều yêu cầu địa chỉ. Vui lòng thử lại sau.",HttpStatus.TOO_MANY_REQUESTS);
    }
    private JsonNode call(String path,Map<String,String> params) {
        quota(); if(!inflight.tryAcquire()) throw unavailable();
        try { JsonNode result=transport.get(path,params); if(result==null || result.isNull()) throw unavailable(); return result; }
        catch(AppException ex) {throw ex;}
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception ex) {throw unavailable();}
        finally {inflight.release();}
    }
    public List<Suggestion> autocomplete(String text) { return suggestions("/api/autocomplete/v4",text); }
    public List<Suggestion> geocode(String text) { return suggestions("/api/search/v4",text); }
    private List<Suggestion> suggestions(String path,String text) {
        text=bounded(text,2,255);
        return suggestions(call(path,Map.of("text",text,"display_type",NEW_ADDRESS_DISPLAY_TYPE)));
    }
    public List<Suggestion> reverse(BigDecimal latitude,BigDecimal longitude) {
        coordinates(latitude,longitude);
        return suggestions(call("/api/reverse/v4",Map.of("lat",latitude.toPlainString(),"lng",longitude.toPlainString(),"display_type",NEW_ADDRESS_DISPLAY_TYPE)));
    }
    private static List<Suggestion> suggestions(JsonNode result) {
        if(!result.isArray()) throw unavailable();
        List<Suggestion> values=new ArrayList<>();
        for(JsonNode n:result) {
            if(values.size()==10) break;
            Suggestion value=suggestion(n);
            if(value!=null) values.add(value);
        }
        return List.copyOf(values);
    }
    private static Suggestion suggestion(JsonNode n) {
        if(!n.isObject()) throw unavailable();
        // A dual-format response may have old data at the root. Keep the new ref and labels together.
        if(n.hasNonNull("data_new")) n=n.get("data_new");
        if(!n.isObject()) throw unavailable();
        JsonNode boundaries=n.path("boundaries");
        if(boundaries.isArray()) {
            for(JsonNode boundary:boundaries) {
                if(boundary.path("type").isIntegralNumber() && boundary.path("type").intValue()==1) return null;
            }
        }
        // Never fall back to data_old, including when data_new is null.
        String ref=text(n,"ref_id"), display=text(n,"display");
        if(ref.isBlank() || display.isBlank() || ref.length()>4096 || display.length()>1000) throw unavailable();
        return new Suggestion(ref,display,text(n,"name"),text(n,"address"));
    }
    public Place place(String refId) {
        refId=bounded(refId,1,4096);
        JsonNode n=call("/api/place/v4",Map.of("refid",refId));
        if(!n.isObject()) throw unavailable();
        // Place inherits the format encoded in refid; display_type cannot convert an old ref here.
        JsonNode districtId=n.path("district_id");
        if(!districtId.isIntegralNumber() || !districtId.canConvertToLong() || !n.path("district").isTextual()) throw unavailable();
        if(districtId.longValue()!=0 || !text(n,"district").isBlank())
            throw new AppException(2456,"Vui lòng tìm và chọn lại địa chỉ mới sau sáp nhập; không sử dụng địa chỉ quận/huyện cũ.",HttpStatus.BAD_REQUEST);
        BigDecimal lat=decimal(n,"lat"),lng=decimal(n,"lng"); coordinates(lat,lng);
        String cityId=positiveId(n,"city_id"),wardId=positiveId(n,"ward_id");
        String display=bounded(text(n,"display"),1,1000),street=bounded(text(n,"street"),0,255);
        String city=bounded(text(n,"city"),1,100),ward=bounded(text(n,"ward"),1,100);
        String name=boundedOptional(text(n,"name"),255),address=bounded(text(n,"address"),1,500);
        // Some concrete address/POI results have a name and coordinates but no parsed street/house number.
        // Preserve those fields as returned instead of inventing a street or rejecting a valid selection.
        if(street.isBlank() && (name==null || name.equalsIgnoreCase(city) || name.equalsIgnoreCase(ward) || name.equalsIgnoreCase(address)))
            throw new AppException(2453,"Vui lòng chọn một đường hoặc địa điểm trong danh sách gợi ý.",HttpStatus.BAD_REQUEST);
        return new Place(refId,display,name,boundedOptional(text(n,"hs_num"),100),street,
                address,cityId,city,null,null,wardId,ward,lat,lng);
    }
    /** Caller has already checked ownership. Client-supplied components/coordinates are never trusted. */
    public void apply(AddressFields target,String refId,String addressNote) {
        String note=boundedOptional(addressNote,255);
        Place p=place(refId);
        target.setRefId(p.refId());target.setDisplay(p.display());target.setName(p.name());
        target.setHouseNumber(p.houseNumber());target.setStreet(p.street());target.setAddress(p.address());
        target.setCityId(p.cityId());target.setCity(p.city());target.setWardId(p.wardId());target.setWard(p.ward());
        target.setDistrictId(p.districtId());target.setDistrict(p.district());
        target.setLatitude(p.latitude());target.setLongitude(p.longitude());target.setAddressNote(note);target.setResolvedAt(Instant.now());
    }
    private static String text(JsonNode n,String key) {return n.path(key).isTextual()?n.path(key).textValue().trim():"";}
    private static BigDecimal decimal(JsonNode n,String key) {if(!n.path(key).isNumber()) throw unavailable(); return n.path(key).decimalValue();}
    private static String positiveId(JsonNode n,String key) {if(!n.path(key).isIntegralNumber() || !n.path(key).canConvertToLong() || n.path(key).longValue()<=0) throw unavailable();return n.path(key).asText();}
    private static String bounded(String value,int min,int max) {
        if(value==null || value.trim().length()<min || value.trim().length()>max || value.chars().anyMatch(Character::isISOControl))
            throw new AppException(2453,"Thông tin địa chỉ chưa hợp lệ. Vui lòng chọn lại một gợi ý khác.",HttpStatus.BAD_REQUEST);
        return value.trim();
    }
    private static String boundedOptional(String value,int max) {return value==null || value.isBlank()?null:bounded(value,1,max);}
    private static void coordinates(BigDecimal lat,BigDecimal lng) {
        if(lat==null || lng==null || lat.abs().compareTo(BigDecimal.valueOf(90))>0 || lng.abs().compareTo(BigDecimal.valueOf(180))>0)
            throw new AppException(2454,"Tọa độ địa chỉ không hợp lệ.",HttpStatus.BAD_REQUEST);
    }
    private static AppException unavailable() {return new AppException(2455,"Không thể xác định địa chỉ từ Vietmap. Vui lòng thử lại; địa chỉ đã lưu chưa bị thay đổi.",HttpStatus.BAD_GATEWAY);}
}
