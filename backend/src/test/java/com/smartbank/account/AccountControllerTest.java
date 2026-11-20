package com.smartbank.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class AccountControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccountService service;
    @MockitoBean com.smartbank.auth.AccessService access;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @Test
    void returnsAccountAsJson() throws Exception {
        UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(service.getAccount(id)).thenReturn(new AccountResponse(
            id, "DEMO-10001", "Demo Customer A", AccountType.CHECKING,
            new BigDecimal("1250.00"), new BigDecimal("1200.00"), new BigDecimal("50.00"),
            BigDecimal.ZERO, "CAD"));
        mvc.perform(get("/api/v1/accounts/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value("DEMO-10001"))
            .andExpect(jsonPath("$.balance").value(1250.00))
            .andExpect(jsonPath("$.availableBalance").value(1200.00));
    }

    @Test
    void missingAccountReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getAccount(id)).thenThrow(new AccountNotFoundException(id));
        mvc.perform(get("/api/v1/accounts/{id}", id))
            .andExpect(status().isNotFound());
    }
}
