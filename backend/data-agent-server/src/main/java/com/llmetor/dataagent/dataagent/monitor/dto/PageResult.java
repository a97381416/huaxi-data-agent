package com.llmetor.dataagent.dataagent.monitor.dto;

import java.util.List;

/**
 * 服务端分页通用壳（监控模块列表接口统一返回形态）。
 *
 * @param total 满足条件的总条数
 * @param page  当前页码（0 起）
 * @param size  每页条数
 * @param items 当前页数据
 */
public record PageResult<T>(long total, int page, int size, List<T> items) {

    public static <T> PageResult<T> of(long total, int page, int size, List<T> items) {
        return new PageResult<>(total, page, size, items);
    }
}
