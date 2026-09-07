package com.hmdp.task;

import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每 5 分钟对账一次：库存一致性核对 + 超时未关单兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReconcileTask {

    private final VoucherOrderServiceImpl voucherOrderService;

    @Scheduled(cron = "0 */5 * * * ?")
    public void reconcile() {
        try {
            Object report = voucherOrderService.reconcile();
            int rescued = voucherOrderService.rescueTimeoutOrders();
            if (rescued > 0) {
                log.warn("[对账] 兜底关单 {} 笔（延迟消息丢失场景）", rescued);
            }
            log.debug("[对账] 完成：{}", report);
        } catch (Exception e) {
            log.error("[对账] 对账任务异常", e);
        }
    }
}