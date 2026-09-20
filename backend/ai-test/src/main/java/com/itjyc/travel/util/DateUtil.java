package com.itjyc.travel.util;

import java.time.LocalDate;

/**
 * 日期工具：给 LLM 注入「今天」，避免「明天/下周」被理解错（LLM 不知道当前日期）。
 */
public final class DateUtil {

    private static final String[] WEEK = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    private DateUtil() {}

    /** 返回「2026年9月18日 星期五」。 */
    public static String today() {
        LocalDate d = LocalDate.now();
        return d.getYear() + "年" + d.getMonthValue() + "月" + d.getDayOfMonth() + "日 "
                + WEEK[d.getDayOfWeek().getValue() - 1];
    }
}
