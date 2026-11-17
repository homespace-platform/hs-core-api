package com.hs.contract.notification;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface OperationalIncidentRepository extends JpaRepository<OperationalIncident,String> {
    @Modifying @Query(value="insert into operational_incidents(id,source_key,component,resource_id,open) values (:id,:key,:component,:resource,false) on conflict (source_key) do nothing",nativeQuery=true)
    void initialize(@Param("id")String id,@Param("key")String key,@Param("component")String component,@Param("resource")String resource);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select i from OperationalIncident i where i.sourceKey=:key")
    Optional<OperationalIncident> lock(@Param("key")String key);
    List<OperationalIncident> findByOrderByOpenedAtDesc(Pageable page);
}
