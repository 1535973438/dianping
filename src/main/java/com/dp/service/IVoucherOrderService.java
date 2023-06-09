package com.dp.service;

import com.dp.dto.Result;
import com.dp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author zzx
 * @since 2022-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);
    public void createVoucherOrder(VoucherOrder voucherOrder);
    public Result createVoucherOrder(Long voucherId);

}
