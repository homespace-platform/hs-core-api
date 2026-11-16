package com.hs.user.service.kyc;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.config.vnpt.VnptKycProperties;
import com.hs.user.integration.vnpt.*;
import com.hs.user.model.VnptKycStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service @RequiredArgsConstructor
public class VnptKycProxy {
    private static final Logger log = LoggerFactory.getLogger(VnptKycProxy.class);
    private final VnptKycClient client;
    private final VnptKycStore store;
    private final VnptKycProperties config;
    private final ObjectMapper mapper;
    private static final Map<String,String> ENDPOINTS=Map.of(
        "card","/ai/v1/web/card/liveness", "ocr","/ai/v1/web/ocr/id", "ocr-front","/ai/v1/web/ocr/id/front",
        "face","/ai/v1/web/face/liveness-3d", "compare","/ai/v1/web/face/compare", "mask","/ai/v1/web/face/mask");

    public JsonNode upload(String userId,String attempt,byte[] image,String contentType) {
        boolean scan3d=contentType!=null && Set.of("text","text/plain").contains(contentType.split(";",2)[0].trim().toLowerCase(Locale.ROOT));
        String type;
        if(scan3d) {
            if(image==null || image.length==0 || image.length>config.maxImageMb()*1048576L)throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
            // VNPT's face SDK uploads its 3D scan as a text Blob before liveness.
            type="text";
        } else type=imageType(image);
        VnptKycStep call=new VnptKycStep();call.setKind(scan3d?"scan3d":"upload");call.setImageDigest(digest(image));
        call.setId(digest((attempt+":upload:"+call.getImageDigest()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        call=store.prepare(userId,attempt,call,List.of());
        if("DONE".equals(call.getState()))return read(call.getResultJson());
        try {
            JsonNode result=client.upload(image,type);
            if(!VnptKycPolicy.technicalSuccess(result)) {
                log.warn("VNPT upload returned code {}", providerCode(result));
                throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
            }
            String hash=reference(result.path("object"),"hash");
            JsonNode sanitized=project(result);
            store.finish(userId,call,write(sanitized),hash);return sanitized;
        } catch(RuntimeException ex){fail(userId,call);throw ex;}
    }
    public JsonNode process(String userId,String attempt,String kind,JsonNode input) {
        if(!ENDPOINTS.containsKey(kind) || input==null || !input.isObject())throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
        ObjectNode body=mapper.createObjectNode();
        body.put("client_session",attempt);body.put("token","e41-1b6d-45c9-9");
        VnptKycStep call=new VnptKycStep();call.setKind(kind);
        List<String> references=new ArrayList<>();
        switch(kind) {
            case "card" -> {String h=reference(input,"img");body.put("img",h);body.put("crop_param","0,0");references.add(h);call.setFrontHash(h);}
            case "ocr", "ocr-front" -> {
                if(!input.path("type").isIntegralNumber() || input.path("type").asInt()!=-1)throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
                String front=reference(input,"img_front");references.add(front);call.setFrontHash(front);body.put("img_front",front);
                if("ocr".equals(kind)){String back=reference(input,"img_back");references.add(back);call.setBackHash(back);body.put("img_back",back);}
                body.put("type",-1);body.put("crop_param","0,0");body.put("validate_postcode",true);
            }
            case "compare" -> {
                String front=reference(input,"img_front"),face=reference(input,"img_face");references.addAll(List.of(front,face));
                call.setFrontHash(front);call.setFaceHash(face);body.put("img_front",front);body.put("img_face",face);
            }
            case "mask" -> {String face=reference(input,"img");references.add(face);call.setFaceHash(face);body.put("img",face);body.putNull("face_bbox");body.putNull("face_lmark");}
            case "face" -> {
                // SDK 3.2.1 sends uploaded file hashes, including the same hash twice for single liveness.
                String near=reference(input,"near_img"),far=reference(input,"far_img");
                call.setNearDigest(digest(near.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                call.setFarDigest(digest(far.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                body.put("near_img",near);body.put("far_img",far);
                String scan=reference(input,"scan3d");references.addAll(List.of(near,far,scan));body.put("scan3d",scan);
            }
            default -> throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
        }
        ObjectNode fingerprint=body.deepCopy();fingerprint.remove("token");
        call.setId(digest((attempt+":"+kind+":"+write(fingerprint)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        call=store.prepare(userId,attempt,call,references);
        if("DONE".equals(call.getState()))return read(call.getResultJson());
        body.put("client_session", call.getClientSession());
        try {
            JsonNode result=client.json(ENDPOINTS.get(kind),body);
            if(!VnptKycPolicy.technicalSuccess(result)) {
                log.warn("VNPT {} returned code {}", kind, providerCode(result));
                throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
            }
            JsonNode sanitized=project(result);store.finish(userId,call,write(sanitized),null);return sanitized;
        }catch(RuntimeException ex){fail(userId,call);throw ex;}
    }
    private void fail(String userId,VnptKycStep call){try{store.fail(userId,call);}catch(RuntimeException ignored){/* Expired/cancelled attempts must stay terminal. */}}
    private static String providerCode(JsonNode result) {
        String code=result==null?"":result.path("message").asText();
        return code.matches("IDG-[A-Z0-9-]{1,24}")?code:"unexpected";
    }
    private JsonNode project(JsonNode result) {
        ObjectNode output=mapper.createObjectNode();output.put("message",result.path("message").asText());
        ObjectNode object=output.putObject("object");
        for(String field:List.of("fake_liveness","fake_print_photo","face_swapping","type_id","back_type_id","match_front_back","hash","liveness","msg","msg_front","msg_back","id","expire_warning","warning","tampering","id_fake_warning","corner_warning","masked","card_type","checking_result_front","checking_result_back"))
            if(result.path("object").has(field))object.set(field,result.path("object").get(field));
        if(write(output).length()>16000)throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
        return output;
    }
    private String imageType(byte[] image) {
        if(image==null || image.length==0 || image.length>config.maxImageMb()*1048576L)throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            var readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext())throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
            var reader=readers.next();
            try {reader.setInput(stream);String name=reader.getFormatName().toLowerCase(Locale.ROOT);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(!Set.of("jpeg","jpg","png").contains(name) || width<1 || height<1 || width>8192 || height>8192 || (long)width*height>25000000)
                    throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
                return "png".equals(name)?"image/png":"image/jpeg";
            }finally{reader.dispose();}
        }catch(AppException ex){throw ex;}catch(Exception ex){throw new AppException(UserErrorCode.KYC_INPUT_INVALID);}
    }
    private static String reference(JsonNode node,String key) {
        JsonNode value=node.path(key);
        if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>512 || value.asText().chars().anyMatch(c -> c<33 || c>126))
            throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
        return value.asText();
    }
    private JsonNode read(String value){try{return mapper.readTree(value);}catch(Exception ex){throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);}}
    private String write(JsonNode value){try{return mapper.writeValueAsString(value);}catch(Exception ex){throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);}}
    public static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
