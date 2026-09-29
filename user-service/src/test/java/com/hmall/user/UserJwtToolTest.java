package com.hmall.user;

import com.hmall.user.utils.JwtTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// hmall.jks 加载验证：用 keystore 里的私钥签一个 RS256 token，再解析回 userId
@SpringBootTest
public class UserJwtToolTest {

    @Autowired
    private JwtTool jwtTool;

    @Test
    void signAndParseWithKeystoreKeyPair() {
        String token = jwtTool.createToken(1234567L, Duration.ofMinutes(5));
        assertNotNull(token, "token 不应为空");
        System.out.println(">>>> RS256 token 前缀：" + token.substring(0, Math.min(40, token.length())));
        assertEquals(1234567L, jwtTool.parseToken(token), "解析出的 userId 应与签发时一致");
    }
}
