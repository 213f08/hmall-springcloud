# hmall — 单体拆微服务练习

黑马 SpringCloud 课程 day03 的练手项目：把电商单体 `hm-service` 按业务边界拆成 5 个独立服务，用 Nacos 做服务注册与发现，服务间调用走 OpenFeign。

## 模块

| 服务 | 端口 | 数据库 | 职责 |
| --- | --- | --- | --- |
| item-service | 8081 | hm-item | 商品 |
| cart-service | 8082 | hm-cart | 购物车 |
| user-service | 8083 | hm-user | 用户、登录、余额 |
| pay-service | 8084 | hm-pay | 支付单 |
| trade-service | 8085 | hm-trade | 订单 |

`hm-common` 是公共模块（统一响应 `R`、异常、`UserContext`、MyBatis/Jackson 配置）；`hm-service` 是拆分前的单体，保留作对照。

## 服务间调用

```
cart  ──▶ item   （查商品）
trade ──▶ item   （查商品、扣库存）
trade ──▶ cart   （下单后清购物车）
pay   ──▶ user   （扣余额）
pay   ──▶ trade  （标记订单已支付）
```

调用方只写服务名（`@FeignClient(value = "item-service")`），地址由 Nacos 解析，没有硬编码 IP。

## 环境

- JDK 21（编译目标 Java 11）、Maven 3.9
- MySQL 8：容器 `sky-mysql`，导入 `resources/` 下的 `hm-item.sql`（数据量大，未入库，需从课件另取）、`hm-cart.sql`、`hm-user.sql`、`hm-pay.sql`、`hm-trade.sql`
- Nacos 2.1.0 standalone，元数据存 MySQL（建库脚本 `resources/nacos.sql`，配置见 `resources/nacos/custom.env`）

## 配置

`application-local.yaml` 和 `hmall.jks`（JWT 签名私钥）不入库。克隆后每个服务复制一份：

```bash
cp item-service/src/main/resources/application-local.example.yaml \
   item-service/src/main/resources/application-local.yaml
# 填 hm.db.host / hm.db.pw；user-service 还要填 hm.jwt.pw
```

`hm.jwt.pw` 是 keystore 口令，需自己生成密钥库：

```bash
keytool -genkeypair -alias hmall -keyalg RSA -keypass <密码> -keystore hmall.jks -storepass <密码>
```

## 启动

MyBatis-Plus 3.4.3 在 JDK 21 上必须加 `--add-opens`，否则启动报错：

```bash
mvn -B install -pl hm-common -am -DskipTests
mvn -B compile -pl item-service,cart-service,user-service,pay-service,trade-service -am
java --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
     -jar item-service/target/item-service.jar --spring.profiles.active=local
```

IDEA 里直接跑各模块的启动类也可以（VM options 加同上参数）。注册是否成功看 Nacos 控制台，或：

```bash
curl "http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20"
```

## 验证

```bash
mvn -B test -pl cart-service  -Dtest=ItemClientTest   -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
mvn -B test -pl trade-service -Dtest=TradeClientTest  -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
mvn -B test -pl pay-service   -Dtest=PayClientTest    -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
mvn -B test -pl user-service  -Dtest=UserJwtToolTest  -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
```

前三个跑之前要先把被调方启动起来（例如测 `TradeClientTest` 需要 item、cart 在跑）。测试用的都是无副作用入参：不存在的订单 id（UPDATE 影响 0 行）、错误支付密码（在第 1 步就抛错）。

## 已知问题

- **登录态传不过去。** `UserContext` 依赖网关写入的 `X-User` 请求头，目前还没有网关，所以 `POST /orders`、`POST /carts` 会卡在 `user_id` 非空约束上，`GET /carts` 静默返回 `[]`。这是拆分的中间状态，网关在 day04。
- **`@Transactional` 已经跨进程了。** `OrderServiceImpl.createOrder` 里的扣库存、清购物车是远程调用，本地事务回滚不了它们，需要 day05 的分布式事务方案。
- **item-service 的异常响应格式和其它服务不一致**：它的启动类在 `com.hmall.item`，默认扫描漏掉了兄弟包 `com.hmall.common.advice`，业务异常返回的是 Spring 原生 `{"timestamp","status","error","path"}`，不是统一的 `{"code","msg","data"}`。补 `@ComponentScan("com.hmall")` 即可。
