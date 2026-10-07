package com.cabinet;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.repo.*;
import com.cabinet.service.RestockService;
import com.cabinet.service.StocktakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 差异核查 HTTP 契约冒烟：HTTP Basic 鉴权、record JSON 反序列化、证据/分摊返回、
 * Swagger OpenAPI 可访问。业务记账逻辑见 {@link DiscrepancyIntegrationTest}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestKafkaConfig.class)
class DiscrepancyControllerSmokeTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestFixtures fx;
    @Autowired private RestockService restockService;
    @Autowired private StocktakeOrderRepository stocktakeRepository;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private record Scene(AppUser operator, Cabinet cabinet, ProductBatch batch) {}

    private Scene setup() {
        // 冒烟测试走真实 HTTP Basic，用户密码需与安全配置的 BCrypt 匹配
        AppUser op = appUserRepository.save(AppUser.builder()
                .username("smoke_op_" + System.nanoTime())
                .password(passwordEncoder.encode("x"))
                .role("OPERATOR").displayName("冒烟运营").build());
        AppUser rep = appUserRepository.save(AppUser.builder()
                .username("smoke_rep_" + System.nanoTime())
                .password(passwordEncoder.encode("x"))
                .role("REPLENISHER").displayName("冒烟补货员").build());
        Supplier supplier = fx.supplier();
        Product product = fx.chilledProduct(supplier);
        Point point = fx.point(LocationType.COMMUNITY);
        Cabinet cabinet = fx.chilledCabinet(point);
        Channel channel = fx.channel(cabinet, product, "A01");
        ProductBatch batch = fx.batch(product, 7, 0);
        RestockOrder rs = restockService.arrive(cabinet.getCabinetNo(), rep.getUsername(),
                "https://img.io/before.jpg", null);
        restockService.placeItem(rs.getOrderNo(), product.getSku(), batch.getBatchNo(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(7),
                10, "A01", "https://img.io/milk.jpg", 4.0);
        restockService.complete(rs.getOrderNo(), null);
        return new Scene(op, cabinet, batch);
    }

    @Test
    void 差异核查全链路HTTP契约与OpenAPI可访问() throws Exception {
        Scene d = setup();
        String cred = d.operator().getUsername() + ":x";
        String[] parts = cred.split(":");

        // 创建盘点单
        String stJson = mockMvc.perform(post("/api/stocktakes")
                        .with(httpBasic(parts[0], parts[1]))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cabinetNo\":\"" + d.cabinet().getCabinetNo() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String stNo = objectMapper.readTree(stJson).get("orderNo").asText();

        // 录入实盘 6（系统 10，差 4）
        String countBody = "{\"counts\":[{\"channelCode\":\"A01\",\"batchNo\":\""
                + d.batch().getBatchNo() + "\",\"countedQty\":6}]}";
        mockMvc.perform(post("/api/stocktakes/" + stNo + "/count")
                        .with(httpBasic(parts[0], parts[1]))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(countBody))
                .andExpect(status().isOk());

        // 立案核查
        String dcJson = mockMvc.perform(post("/api/discrepancies/from-stocktake")
                        .with(httpBasic(parts[0], parts[1]))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stocktakeNo\":\"" + stNo + "\",\"channelCode\":\"A01\",\"batchNo\":\""
                                + d.batch().getBatchNo() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementFrozen").value(true))
                .andExpect(jsonPath("$.diffQty").value(4))
                .andReturn().getResponse().getContentAsString();
        String dcNo = objectMapper.readTree(dcJson).get("discrepancyNo").asText();

        // 证据回放
        mockMvc.perform(get("/api/discrepancies/" + dcNo + "/evidence")
                        .with(httpBasic(parts[0], parts[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restockItems[0].photoUrl").value("https://img.io/milk.jpg"))
                .andExpect(jsonPath("$.candidateCauses").isArray());

        // 拆分：3 瓶确认 + 1 瓶挂起
        String lines = "{\"lines\":["
                + "{\"cause\":\"RECOGNITION_MISSED\",\"liability\":\"PLATFORM\",\"qty\":1,"
                + "\"occurredAt\":\"2026-10-06T13:05:00\",\"remark\":\"漏单1瓶\"},"
                + "{\"cause\":\"RESTOCK_SHORTAGE\",\"liability\":\"REPLENISHER\",\"qty\":2,"
                + "\"occurredAt\":\"2026-10-06T08:10:00\",\"remark\":\"少放2瓶\"},"
                + "{\"cause\":\"UNCONFIRMED\",\"liability\":\"NONE\",\"qty\":1,"
                + "\"occurredAt\":\"2026-10-06T16:00:00\",\"remark\":\"挂起\"}]}";
        mockMvc.perform(post("/api/discrepancies/" + dcNo + "/allocations")
                        .with(httpBasic(parts[0], parts[1]))
                        .contentType(MediaType.APPLICATION_JSON).content(lines))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(get("/api/discrepancies/" + dcNo)
                        .with(httpBasic(parts[0], parts[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_CONFIRMED"))
                .andExpect(jsonPath("$.suspendedQty").value(1));

        // 未认证被拒
        mockMvc.perform(get("/api/discrepancies")).andExpect(status().isUnauthorized());

        // OpenAPI 文档包含差异核查接口
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./api/discrepancies").exists())
                .andExpect(jsonPath("$.tags[?(@.name=='盘点差异核查')]").exists());
    }
}
