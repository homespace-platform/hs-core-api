package com.hs.contract.service;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
/** Only a dedicated, opt-in test DB may be created/altered. No operational schema access. */
@EnabledIfEnvironmentVariable(named="HS_WORKFLOW_UPGRADE_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_workflow_upgrade_test")
class RentalWorkflowSchemaUpgradeTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(excludeName={"org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration","org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration","org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration"}) @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses={ContractRepository.class,RentalRequestRepository.class,PaymentRequestRepository.class,StorageObjectRepository.class})
    @EnableJpaAuditing static class Config {}
    ConfigurableApplicationContext start(String ddl){return new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
        "--spring.config.location=optional:classpath:/workflow-upgrade-test-only.properties",
        "--spring.datasource.url="+System.getenv("HS_WORKFLOW_UPGRADE_PG_URL"),"--spring.datasource.username=postgres","--spring.datasource.password=hs_workflow_test",
        "--spring.jpa.hibernate.ddl-auto="+ddl,"--eureka.client.enabled=false","--spring.cloud.discovery.enabled=false","--spring.jpa.show-sql=false","--logging.level.root=WARN","--spring.main.banner-mode=off");}
    @Test void upgradePreservesLegacyDataAndTwoInstancesUseDatabaseLocksAcrossRestart() throws Exception {
        String contractId="11111111-1111-1111-1111-111111111111",workflowId="rental:MOVE_IN";
        try(var before=start("create")){
            var repo=before.getBean(ContractRepository.class);repo.saveAndFlush(Contract.builder().id(contractId).contractNumber("UPGRADE-TEST-ONLY").rentalRequestId("rental").listingId("listing").landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("v1").status(ContractStatus.ACTIVE).paymentStatus(ContractPaymentStatus.PAID).build());
            var w=new RentalWorkflowRecord();w.setId(workflowId);w.setRentalRequestId("rental");w.setContractId(contractId);w.setKind("MOVE_IN");w.setStatus("PROPOSED");w.setRevision(7);w.setPayloadJson("{\"reason\":\"Căn cứ đã ghi trước nâng cấp\"}");w.setHistoryJson("[]");before.getBean(RentalWorkflowRecordRepository.class).saveAndFlush(w);
        }
        try(var db=DriverManager.getConnection(System.getenv("HS_WORKFLOW_UPGRADE_PG_URL"),"postgres","hs_workflow_test");var sql=db.createStatement()){
            sql.execute("drop table workflow_signing_documents");sql.execute("alter table rental_workflow_records drop column calculation_json");
            sql.execute("alter table contracts drop column agreed_early_end_exclusive, drop column agreed_early_billing_months, drop column mutual_termination_workflow_id");
        }
        try(var upgraded=start("update");var second=start("validate")){
            var c=upgraded.getBean(ContractRepository.class).findById(contractId).orElseThrow();assertEquals(ContractStatus.ACTIVE,c.getStatus());assertNull(c.getAgreedEarlyEndExclusive());
            var w=upgraded.getBean(RentalWorkflowRecordRepository.class).findById(workflowId).orElseThrow();assertEquals(7,w.getRevision());assertNull(w.getCalculationJson());assertTrue(w.getPayloadJson().contains("Căn cứ đã ghi"));assertEquals(0,upgraded.getBean(WorkflowSigningDocumentRepository.class).count());
            var pool=Executors.newFixedThreadPool(2);
            try{var a=pool.submit(()->increment(upgraded,contractId,workflowId));var b=pool.submit(()->increment(second,contractId,workflowId));a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        }
        try(var restarted=start("validate")){
            var w=restarted.getBean(RentalWorkflowRecordRepository.class).findById(workflowId).orElseThrow();assertEquals(17,w.getRevision());assertEquals("PROPOSED",w.getStatus());assertTrue(w.getPayloadJson().contains("Căn cứ đã ghi"));
        }
    }
    void increment(ConfigurableApplicationContext app,String id,String workflow){for(int n=0;n<5;n++)new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{app.getBean(ContractRepository.class).findByIdForUpdate(id).orElseThrow();var repo=app.getBean(RentalWorkflowRecordRepository.class);var w=repo.findById(workflow).orElseThrow();w.setRevision(w.getRevision()+1);repo.saveAndFlush(w);});}
}
