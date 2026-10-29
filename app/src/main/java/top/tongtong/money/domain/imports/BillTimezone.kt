package top.tongtong.money.domain.imports

import java.time.ZoneId

/**
 * 微信账单邮件与账单表格里的时间恒为北京时间（UTC+8），与设备时区无关，
 * 因此解析侧固定使用 Asia/Shanghai。账本周期结算与月份归属跟随系统时区
 * （见 AGENTS.md 的约定），设备处于中国时区时两者一致；若未来要支持海外
 * 时区设备，需要把周期结算一并改为固定时区，属于单独评审的语义变更。
 */
val BILL_TIMEZONE: ZoneId = ZoneId.of("Asia/Shanghai")
