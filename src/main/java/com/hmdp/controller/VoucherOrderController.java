package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 秒杀优惠券
     * @param voucherId 优惠券id
     * @return 订单id
     */
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 模拟支付（第三方支付成功回调）
     */
    @PostMapping("pay/{id}")
    public Result pay(@PathVariable("id") Long orderId) {
        return voucherOrderService.payOrder(orderId);
    }

    /**
     * 查询订单状态（仅本人）
     */
    @GetMapping("{id}")
    public Result queryOrder(@PathVariable("id") Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return Result.fail("订单不存在或无权操作");
        }
        return Result.ok(order);
    }

    /**
     * 我的订单列表（分页）
     */
    @GetMapping("list")
    public Result listOrders(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        Long userId = UserHolder.getUser().getId();
        Page<VoucherOrder> page = voucherOrderService.query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    /**
     * 手动触发对账（演示/压测后核对用）
     */
    @PostMapping("reconcile")
    public Result reconcile() {
        Map<String, Object> report = voucherOrderService.reconcile();
        report.put("rescuedTimeoutOrders", voucherOrderService.rescueTimeoutOrders());
        return Result.ok(report);
    }
}
