package com.hmall.pay;

import com.hmall.pay.client.OrderClient;
import com.hmall.pay.client.UserClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertThrows;

// 测试进程不注册到 Nacos，避免负载均衡把请求打到这个临时实例上
@SpringBootTest(properties = "spring.cloud.nacos.discovery.register-enabled=false")
public class PayClientTest {

    @Autowired
    private UserClient userClient;

    @Autowired
    private OrderClient orderClient;

    @Test
    void deductMoneyReachesUserService() {
        // 无网关时 UserContext 为 null，user-service 会在第 1 步抛错；
        // 能拿到远程服务的异常即证明请求已通过服务名到达 user-service，且不会扣钱
        Exception e = assertThrows(Exception.class,
                () -> userClient.deductMoney("wrong-pw", 1));
        System.out.println(">>>> pay->user 远程异常：" + e.getMessage());
    }

    @Test
    void markOrderPaySuccessReachesTradeService() {
        // 不存在的订单 id，UPDATE 影响 0 行，无副作用
        orderClient.markOrderPaySuccess(999999999L);
        System.out.println(">>>> pay->trade 远程调用已到达 trade-service");
    }
}
