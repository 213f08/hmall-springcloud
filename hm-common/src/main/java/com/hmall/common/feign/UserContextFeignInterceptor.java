package com.hmall.common.feign;

import com.hmall.common.utils.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * OpenFeign 发起的每个请求都先经过这里，把当前登录用户塞进请求头，传给下游微服务
 */
public class UserContextFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 1.获取当前登录用户
        Long userId = UserContext.getUser();
        if (userId == null) {
            return;
        }
        // 2.保存到请求头
        template.header("user-info", userId.toString());
    }
}
