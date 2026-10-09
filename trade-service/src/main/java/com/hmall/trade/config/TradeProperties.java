package com.hmall.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "hm.trade")
public class TradeProperties {

    /**
     * 下单之后过多久回头检查支付状态、超时未付款就关单。
     * 做成配置项（而不是写死在代码里）有两个原因：
     * 1）课程理论值是 15~30 分钟，不同业务想要的值不一样；
     * 2）本地验证时可以临时改成 10s，改 Nacos 立刻生效，不用改代码、也不用重启。
     */
    @DurationUnit(ChronoUnit.MINUTES)
    private Duration orderDelay = Duration.ofMinutes(15);
}
