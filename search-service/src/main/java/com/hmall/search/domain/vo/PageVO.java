package com.hmall.search.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索分页结果。
 * 刻意不复用 hm-common 的 PageDTO：那个类到处引用 MyBatis-Plus 的 Page，
 * 而 mybatis-plus 在 hm-common 里是 provided 依赖，搜索服务不连数据库所以类路径上没有 Page。
 * 直接返回 PageDTO 时 Jackson 反射扫描它会抛 NoClassDefFoundError，接口报 500。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageVO<T> {

    private Long total;

    private Long pages;

    private List<T> list;
}
