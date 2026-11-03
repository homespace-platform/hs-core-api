package com.hs.contract.service;

import com.hs.contract.dto.billing.*;
import com.hs.contract.dto.complaint.*;
import com.hs.contract.model.constant.ContractStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Physical possession is a bilateral record, independently of lease/deposit signatures. */
@Service @RequiredArgsConstructor
public class MoveInHandoverService {
    private final RentalWorkflowSupport support;
    private final ComplaintCaseService complaints;

    @Transactional public RentalWorkflowView get(String rental,String actor){return view(support.lock(rental,actor,"MOVE_IN"));}
    @Transactional public RentalWorkflowView propose(String rental,String actor,RentalWorkflowPayloads.Handover p){
        var s=support.lock(rental,actor,"MOVE_IN");support.version(s,p.expectedRevision());support.ownerOnly(s);eligible(s);
        if(p.handedOverAt()==null||p.handedOverAt().isAfter(support.time.now())
            ||p.handedOverAt().isBefore(s.rental().getMoveInDate().atStartOfDay(com.hs.common.time.BillingTime.ZONE).toInstant()))throw support.invalid("Ngày nhận nhà thực tế phải nằm trong thời gian thuê và không ở tương lai.");
        support.text(p.condition());
        if(p.keys()<0||p.keys()>1000||p.inventory()==null||p.inventory().size()>100)throw support.invalid("Danh sách tài sản hoặc số chìa khóa không hợp lệ.");
        for(var item:p.inventory())if(item==null||item.name()==null||item.name().isBlank()||item.name().length()>150||item.quantity()<0||item.quantity()>10000||item.condition()==null||item.condition().isBlank()||item.condition().length()>500)throw support.invalid("Cần ghi rõ tên, số lượng và hiện trạng từng tài sản.");
        for(var meter:Arrays.asList(p.electricity(),p.water()))if(meter!=null&&(meter.signum()<0||meter.stripTrailingZeros().scale()>3||meter.precision()-meter.scale()>12))throw support.invalid("Chỉ số công tơ phải không âm, tối đa 3 chữ số thập phân.");
        // Capture readings only. Never overwrite signed snapshots, issued invoices or the billing baseline.
        support.propose(s,p,p.reason(),support.evidence(s,p.evidenceIds()),null);return view(s);
    }
    @Transactional public RentalWorkflowView act(String rental,String actor,String action,RentalWorkflowPayloads.Action p){
        var s=support.lock(rental,actor,"MOVE_IN");support.version(s,p.expectedRevision());eligible(s);var w=s.record();String reason=support.text(p.reason());
        if("CONFIRM".equals(action)){
            support.acknowledge(p);
            if(!"PROPOSED".equals(w.getStatus())||!Objects.equals(w.getSourceRevisionId(),s.contract().getCurrentRevisionId()))throw support.invalid("Cần xem đúng phiên bản biên bản và hợp đồng hiện tại.");
            if(support.owner(s)){if(w.getOwnerConfirmedAt()!=null)throw support.invalid("Bạn đã xác nhận bản này.");w.setOwnerConfirmedAt(support.time.now());}
            else {if(w.getTenantConfirmedAt()!=null)throw support.invalid("Bạn đã xác nhận bản này.");w.setTenantConfirmedAt(support.time.now());}
            if(w.getOwnerConfirmedAt()!=null&&w.getTenantConfirmedAt()!=null)w.setStatus("COMPLETED");
        }else if("REPORT_ISSUE".equals(action)){
            if(w.getRevision()==0){w.setContractId(s.contract().getId());w.setSourceRevisionId(s.contract().getCurrentRevisionId());support.save(s,"REPORT_REQUESTED",reason,List.of());}
            var c=complaints.openFromWorkflow(rental,actor,"MOVE_IN",w.getRevision(),reason);w.setComplaintId(c.id());w.setStatus("DISPUTED");
        }else throw support.invalid("Thao tác không hợp lệ.");
        support.save(s,action,reason,List.of());return view(s);
    }
    private void eligible(RentalWorkflowSupport.Source s){if(!Set.of(ContractStatus.ACTIVE,ContractStatus.EXPIRED).contains(s.contract().getStatus())||s.contract().getSignedAt()==null)throw support.invalid("Chỉ lập/xác nhận nhận nhà khi HĐ thuê đã ký đủ; hồ sơ kết thúc chỉ được tra cứu.");}
    private RentalWorkflowView view(RentalWorkflowSupport.Source s){
        var actions=new ArrayList<String>();String blocked=null;var w=s.record();
        if(Set.of(ContractStatus.ACTIVE,ContractStatus.EXPIRED).contains(s.contract().getStatus())&&s.contract().getSignedAt()!=null){
            if(support.owner(s))actions.add("PROPOSE");
            if("PROPOSED".equals(w.getStatus())&&(support.owner(s)?w.getOwnerConfirmedAt()==null:w.getTenantConfirmedAt()==null))actions.add("CONFIRM");
            actions.add("REPORT_ISSUE");
        }else blocked="Biên bản nhận nhà độc lập với chữ ký hợp đồng; cần HĐ thuê đã ký đủ.";
        return support.view(s,actions,blocked,"COMPLETED".equals(w.getStatus())?"TRANSFERRED_TO_LEASE":"HANDOVER_PENDING",List.of());
    }
}
