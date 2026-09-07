package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_stock_reconcile_log")
public class StockReconcileLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 秒杀券id
     */
    private Long voucherId;

    /**
     * Redis 库存
     */
    private Integer redisStock;

    /**
     * DB 库存
     */
    private Integer dbStock;

    /**
     * DB 有效订单数（status=1 + status=2）
     */
    private Long dbOrderCount;

    /**
     * Redis 判重集合大小
     */
    private Long redisOrderCount;

    /**
     * 差异描述（方便日志和后续排查）
     */
    private String diffDesc;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    // 以下字段可根据需要添加（可选）
    private String remark;   // 备注
}