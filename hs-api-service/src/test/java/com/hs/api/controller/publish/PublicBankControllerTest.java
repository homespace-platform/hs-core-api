package com.hs.api.controller.publish;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.hs.payment.dto.BankResponse;
import com.hs.payment.service.qr.VietQrBankService;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicBankControllerTest {
    @Test
    void servesBanksWithCacheHeader() throws Exception {
        var svc = mock(VietQrBankService.class);
        when(svc.getBanks()).thenReturn(List.of(new BankResponse("970415", "ICB", "Cong thuong", "VietinBank", "https://l/i.png")));
        standaloneSetup(new PublicBankController(svc)).build().perform(get("/public/banks"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(jsonPath("$.result[0].bin").value("970415"))
                .andExpect(jsonPath("$.result[0].shortName").value("VietinBank"));
    }
}
