package com.dp.service.impl;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.Result;
import com.dp.dto.UserDTO;
import com.dp.entity.SeckillVoucher;
import com.dp.entity.VoucherOrder;
import com.dp.mapper.VoucherOrderMapper;
import com.dp.service.ISeckillVoucherService;
import com.dp.service.IVoucherOrderService;
import com.dp.utils.RedisIdWorker;
import com.dp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zzx
 * @since 2022-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private IVoucherOrderService proxy;//代理对象

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //    private final BlockingQueue<VoucherOrder> ordersTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    VoucherOrder voucherOrder =ordersTasks.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    //获取消息队列中信息
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if (read == null || read.isEmpty()) {
                        //获取失败，进入下一次循环
                        continue;
                    }
                    //获取成功，可以下单
                    MapRecord<String, Object, Object> mapRecord = read.get(0);
                    Map<Object, Object> values = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", mapRecord.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //获取消息队列中信息
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if (read == null || read.isEmpty()) {
                        //获取失败，进入pending-list 中没有消息
                        break;
                    }
                    //获取成功，可以下单
                    MapRecord<String, Object, Object> mapRecord = read.get(0);
                    Map<Object, Object> values = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", mapRecord.getId());
                } catch (Exception e1) {
                    log.error("处理pending-list订单异常",e1);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        //获取代理对象（事务）
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        long orderId = redisIdWorker.nextId("order");
        Long flag = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(), voucherId.toString(), String.valueOf(user.getId()), String.valueOf(orderId));
        int res = flag.intValue();
        if (res != 0) {
            return res == 1 ? Result.fail("无库存") : Result.fail("一人只能购买一次");
        }

//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        Long userId = user.getId();
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        ordersTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0)
            log.error("不允许重复下单");
        //扣库存
        boolean success = seckillVoucherService.
                update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success)
            log.error("库存不足");
        //创订单
        save(voucherOrder);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询相关秒杀券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("未开始");
//        }
//        //判断是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("结束");
//        }
//        //判断剩余
//        if (voucher.getStock() < 1) {
//            return Result.fail("无库存");
//        }
//        //使用redis锁
//        Long userId = UserHolder.getUser().getId();
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
////        boolean isLock = simpleRedisLock.tryLock(5);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock)
//            return Result.fail("一人只能下一单");
//        //获取代理对象（事务）
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }
//
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0)
            return Result.fail("已经购买过");
        //扣库存
        boolean success = seckillVoucherService.
                update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success)
            return Result.fail("库存不足");
        //创订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long order = redisIdWorker.nextId("order");
        voucherOrder.setId(order);
        Long user = UserHolder.getUser().getId();
        voucherOrder.setUserId(user);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //返回订单id
        return Result.ok(order);
    }
}
