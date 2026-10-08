package com.bookkeeping.bill;

import com.bookkeeping.constant.AccountType;
import com.bookkeeping.constant.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 识别微信、支付宝「用于个人对账」导出的表头，跳过前面的说明行。
 * 不处理用作证明材料的 PDF，也不解压官方加密压缩包。
 */
public final class PaymentBillParser {

    public static final String SOURCE_WECHAT = "wechat";
    public static final String SOURCE_ALIPAY = "alipay";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss");
    private static final DateTimeFormatter DATE_MINUTE = DateTimeFormatter.ofPattern("yyyy-M-d H:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-M-d");
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private PaymentBillParser() {
    }

    public record BillDocument(String source, List<BillEntry> entries) {
    }

    /**
     * @param kind data / ignored / invalid
     */
    public record BillEntry(
            int line,
            String kind,
            String reason,
            String dateText,
            String typeText,
            String amountText,
            String accountName,
            AccountType accountType,
            String toAccountName,
            AccountType toAccountType,
            String categoryHint,
            String note,
            LocalDateTime date,
            TransactionType type,
            BigDecimal amount,
            String sourceId
    ) {
    }

    /** 找不到账单表头时返回 null，调用方再按本应用 CSV 解析。 */
    public static BillDocument tryParse(List<List<String>> grid) {
        if (grid == null || grid.isEmpty()) {
            return null;
        }
        int limit = Math.min(grid.size(), 80);
        Header header = null;
        int headerAt = -1;
        for (int i = 0; i < limit; i++) {
            Header detected = Header.detect(grid.get(i));
            if (detected != null) {
                header = detected;
                headerAt = i;
                break;
            }
        }
        if (header == null) {
            return null;
        }
        List<BillEntry> entries = new ArrayList<>();
        for (int i = headerAt + 1; i < grid.size(); i++) {
            BillEntry entry = header.parse(i + 1, grid.get(i));
            if (entry != null) {
                entries.add(entry);
            }
        }
        return new BillDocument(header.source, entries);
    }

    private static final class Header {
        private final String source;
        private final int time;
        private final int type;
        private final int category;
        private final int party;
        private final int goods;
        private final int direction;
        private final int amount;
        private final int pay;
        private final int status;
        private final int orderId;
        private final int remark;

        private Header(String source, int time, int type, int category, int party, int goods, int direction,
                       int amount, int pay, int status, int orderId, int remark) {
            this.source = source;
            this.time = time;
            this.type = type;
            this.category = category;
            this.party = party;
            this.goods = goods;
            this.direction = direction;
            this.amount = amount;
            this.pay = pay;
            this.status = status;
            this.orderId = orderId;
            this.remark = remark;
        }

        private static Header detect(List<String> row) {
            if (row == null || row.size() < 4) {
                return null;
            }
            int time = col(row, "交易时间");
            int amount = col(row, "金额");
            if (time < 0 || amount < 0) {
                return null;
            }
            int order = col(row, "交易订单号");
            int payMethod = col(row, "收/付款方式");
            int wechatOrder = col(row, "交易单号");
            int wechatPay = col(row, "支付方式");
            boolean alipay = order >= 0 || payMethod >= 0;
            boolean wechat = wechatOrder >= 0 || (wechatPay >= 0 && col(row, "当前状态") >= 0);
            if (!alipay && !wechat) {
                return null;
            }
            String source = alipay ? SOURCE_ALIPAY : SOURCE_WECHAT;
            int orderId = alipay
                    ? first(order, col(row, "商家订单号"))
                    : first(wechatOrder, col(row, "商户单号"));
            if (orderId < 0) {
                return null;
            }
            return new Header(
                    source,
                    time,
                    col(row, "交易类型"),
                    col(row, "交易分类"),
                    col(row, "交易对方"),
                    first(col(row, "商品"), col(row, "商品说明")),
                    first(col(row, "收/支"), col(row, "收入/支出"), col(row, "收支")),
                    amount,
                    first(payMethod, wechatPay, col(row, "付款方式"), col(row, "收款方式")),
                    first(col(row, "当前状态"), col(row, "交易状态")),
                    orderId,
                    col(row, "备注")
            );
        }

        private BillEntry parse(int line, List<String> row) {
            if (row == null || blank(row)) {
                return null;
            }
            String dateRaw = cell(row, time);
            String amountRaw = cell(row, amount);
            LocalDateTime date = parseDate(dateRaw);
            BigDecimal amountValue = parseAmount(amountRaw);
            if (date == null && amountValue == null) {
                return null;
            }
            String shownAmount = amountValue == null
                    ? amountRaw
                    : amountValue.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
            String typeText = cell(row, type);
            String categoryText = cell(row, category);
            String goodsText = cell(row, goods);
            String directionText = cell(row, direction);
            String statusText = cell(row, status);
            String payRaw = cell(row, pay);
            String sourceId = cleanId(cell(row, orderId));
            String hint = !categoryText.isEmpty() ? categoryText : typeText;
            String noteText = buildNote(cell(row, party), goodsText, cell(row, remark));
            String account = canonicalAccount(payRaw, source);
            AccountType payType = accountType(account, source);

            if (statusText.contains("关闭")) {
                return ignored(line, dateRaw, directionText, shownAmount, account, payType, hint, noteText, date, sourceId,
                        "交易关闭，已跳过");
            }
            if (statusText.contains("失败")) {
                return ignored(line, dateRaw, directionText, shownAmount, account, payType, hint, noteText, date, sourceId,
                        "交易未成功，已跳过");
            }
            if (statusText.contains("全额退款")) {
                return ignored(line, dateRaw, directionText, shownAmount, account, payType, hint, noteText, date, sourceId,
                        "已全额退款，已跳过");
            }
            if (contains(typeText, "信用卡还款") || contains(categoryText, "信用卡还款") || contains(goodsText, "信用卡还款")) {
                return ignored(line, dateRaw, directionText, shownAmount, account, payType, hint, noteText, date, sourceId,
                        "信用卡还款不导入，避免和银行卡入账重复");
            }
            if (date == null) {
                return invalid(line, dateRaw, directionText, amountRaw, account, hint, noteText, "日期「" + dateRaw + "」无法解析");
            }
            if (amountValue == null) {
                return invalid(line, dateRaw, directionText, amountRaw, account, hint, noteText,
                        amountRaw.isEmpty() ? "缺少金额" : "金额「" + amountRaw + "」格式非法");
            }
            if (amountValue.signum() == 0) {
                return invalid(line, dateRaw, directionText, amountRaw, account, hint, noteText, "金额须大于 0");
            }
            if (sourceId.isEmpty()) {
                return invalid(line, dateRaw, directionText, amountRaw, account, hint, noteText, "缺少交易单号");
            }

            String amountText = shownAmount;
            String dateText = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            boolean neutral = isNeutral(directionText);
            boolean withdraw = containsWithdraw(typeText) || containsWithdraw(categoryText)
                    || (neutral && containsWithdraw(goodsText));
            boolean topup = containsTopup(typeText) || containsTopup(categoryText)
                    || (neutral && containsTopup(goodsText));
            if (withdraw || topup) {
                return transfer(line, dateText, amountText, account, payType, hint, noteText, date, amountValue,
                        sourceId, withdraw);
            }
            if (isExpense(directionText)) {
                return data(line, dateText, "支出", amountText, account, payType, "", null, hint, noteText, date,
                        TransactionType.EXPENSE, amountValue, sourceId, "");
            }
            if (isIncome(directionText)) {
                return data(line, dateText, "收入", amountText, account, payType, "", null, hint, noteText, date,
                        TransactionType.INCOME, amountValue, sourceId, "");
            }
            if (neutral) {
                return ignored(line, dateText, directionText.isEmpty() ? "不计收支" : directionText, amountText, account,
                        payType, hint, noteText, date, sourceId, "不计收支，已跳过");
            }
            return invalid(line, dateText, directionText, amountText, account, hint, noteText, "无法识别收支「" + directionText + "」");
        }

        private BillEntry transfer(int line, String dateText, String amountText, String payName, AccountType payType,
                                   String hint, String note, LocalDateTime date, BigDecimal amount, String sourceId,
                                   boolean withdraw) {
            String wallet = SOURCE_WECHAT.equals(source) ? "微信零钱" : "支付宝";
            AccountType walletType = SOURCE_WECHAT.equals(source) ? AccountType.WECHAT_PAY : AccountType.ALI_PAY;
            String from = withdraw ? wallet : payName;
            AccountType fromType = withdraw ? walletType : payType;
            String to = withdraw ? payName : wallet;
            AccountType toType = withdraw ? payType : walletType;
            if (from.equals(to)) {
                return ignored(line, dateText, "转账", amountText, from, fromType, hint, note, date, sourceId,
                        "无法确定对手账户，已跳过");
            }
            return data(line, dateText, "转账", amountText, from, fromType, to, toType, hint, note, date,
                    TransactionType.TRANSFER, amount, sourceId, "");
        }
    }

    private static BillEntry data(int line, String dateText, String typeText, String amountText, String account,
                                  AccountType accountType, String toAccount, AccountType toType, String hint,
                                  String note, LocalDateTime date, TransactionType type, BigDecimal amount,
                                  String sourceId, String reason) {
        return new BillEntry(line, "data", reason, dateText, typeText, amountText, account, accountType, toAccount,
                toType, hint, note, date, type, amount, sourceId);
    }

    private static BillEntry ignored(int line, String dateText, String typeText, String amountText, String account,
                                     AccountType accountType, String hint, String note, LocalDateTime date,
                                     String sourceId, String reason) {
        return new BillEntry(line, "ignored", reason, dateText, typeText, amountText, account, accountType, "",
                null, hint, note, date, null, null, sourceId);
    }

    private static BillEntry invalid(int line, String dateText, String typeText, String amountText, String account,
                                     String hint, String note, String reason) {
        return new BillEntry(line, "invalid", reason, dateText, typeText, amountText, account, null, "",
                null, hint, note, null, null, null, "");
    }

    private static int first(int... indexes) {
        for (int index : indexes) {
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    private static int col(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (name.equals(normHeader(header.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    static String normHeader(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\uFEFF", "").strip().replace(" ", "").replace("\u3000", "").replace("\u00A0", "");
        text = text.replace('（', '(').replace('）', ')');
        int paren = text.indexOf('(');
        if (paren > 0) {
            text = text.substring(0, paren);
        }
        return text;
    }

    static String canonicalAccount(String raw, String source) {
        String text = raw == null ? "" : raw.strip();
        String compact = text.replace(" ", "").replace("\u3000", "");
        if (compact.isEmpty() || compact.equals("/") || compact.equals("-") || compact.equals("无")) {
            return SOURCE_WECHAT.equals(source) ? "微信零钱" : "支付宝";
        }
        if (compact.equals("零钱") || compact.equals("微信零钱") || compact.equals("微信钱包")) {
            return "微信零钱";
        }
        if (compact.equals("余额") || compact.equals("账户余额") || compact.equals("支付宝余额") || compact.equals("支付宝")) {
            return "支付宝";
        }
        return text;
    }

    static AccountType accountType(String name, String source) {
        String compact = name.replace(" ", "");
        if (compact.contains("银行") || compact.contains("信用卡") || compact.contains("储蓄卡")
                || compact.contains("借记卡") || compact.contains("贷记卡")) {
            return AccountType.BANK;
        }
        if (compact.contains("花呗") || compact.contains("余额宝") || compact.contains("支付宝") || compact.equals("余额")) {
            return AccountType.ALI_PAY;
        }
        if (compact.contains("零钱") || compact.contains("微信")) {
            return AccountType.WECHAT_PAY;
        }
        return SOURCE_ALIPAY.equals(source) ? AccountType.ALI_PAY : AccountType.WECHAT_PAY;
    }

    private static String cell(List<String> row, int index) {
        if (index < 0 || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return row.get(index).strip();
    }

    private static boolean blank(List<String> row) {
        for (String value : row) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(String text, String token) {
        return text != null && text.contains(token);
    }

    private static boolean containsWithdraw(String text) {
        return text != null && text.contains("提现");
    }

    private static boolean containsTopup(String text) {
        return text != null && text.contains("充值") && !text.contains("充值缴费") && !text.contains("话费");
    }

    private static boolean isExpense(String direction) {
        return "支出".equals(direction) || "支".equals(direction);
    }

    private static boolean isIncome(String direction) {
        return "收入".equals(direction) || "收".equals(direction);
    }

    private static boolean isNeutral(String direction) {
        if (direction == null || direction.isBlank()) {
            return true;
        }
        return "不计收支".equals(direction) || "/".equals(direction) || "／".equals(direction)
                || "-".equals(direction) || "无".equals(direction);
    }

    private static String cleanId(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();
        if (text.startsWith("'") || text.startsWith("`")) {
            text = text.substring(1).strip();
        }
        if (text.matches("\\d+\\.0+")) {
            text = text.substring(0, text.indexOf('.'));
        }
        return text;
    }

    private static String buildNote(String party, String goods, String remark) {
        List<String> parts = new ArrayList<>();
        addNote(parts, party);
        addNote(parts, goods);
        addNote(parts, remark);
        if (parts.isEmpty()) {
            return "";
        }
        String joined = String.join(" · ", parts);
        return joined.length() <= 200 ? joined : joined.substring(0, 200);
    }

    private static void addNote(List<String> parts, String raw) {
        if (raw == null) {
            return;
        }
        String text = raw.strip();
        if (text.isEmpty() || text.equals("/") || text.equals("-") || text.equals("无")) {
            return;
        }
        if (!parts.contains(text)) {
            parts.add(text);
        }
    }

    static LocalDateTime parseDate(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.strip();
        if (raw.isEmpty()) {
            return null;
        }
        String normalized = raw.replace('/', '-');
        LocalDateTime dateTime = tryDateTime(normalized, DATE_TIME);
        if (dateTime != null) {
            return dateTime;
        }
        dateTime = tryDateTime(normalized, DATE_MINUTE);
        if (dateTime != null) {
            return dateTime;
        }
        try {
            return LocalDate.parse(normalized, DATE).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // 继续尝试紧凑日期和 Excel 序列号
        }
        try {
            return LocalDate.parse(raw, COMPACT).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // 继续尝试 Excel 序列号
        }
        if (raw.matches("\\d{5}(\\.\\d+)?")) {
            double serial = Double.parseDouble(raw);
            if (serial >= 20000 && serial < 80000) {
                return excelSerial(serial);
            }
        }
        return null;
    }

    private static LocalDateTime tryDateTime(String text, DateTimeFormatter formatter) {
        try {
            return LocalDateTime.parse(text, formatter);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Excel 序列号：1899-12-30 起算，和 1900 闰年兼容写法对齐。 */
    private static LocalDateTime excelSerial(double serial) {
        long days = (long) Math.floor(serial);
        int seconds = (int) Math.round((serial - days) * 86400);
        if (seconds >= 86400) {
            days += 1;
            seconds -= 86400;
        }
        return LocalDate.of(1899, 12, 30).plusDays(days).atStartOfDay().plusSeconds(seconds);
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip()
                .replace("¥", "")
                .replace("￥", "")
                .replace("元", "")
                .replace(",", "")
                .replace("，", "")
                .replace(" ", "")
                .replace("\u00A0", "");
        if (text.startsWith("+")) {
            text = text.substring(1);
        }
        if (text.isEmpty() || text.equals("-") || text.equals("/")) {
            return null;
        }
        try {
            return new BigDecimal(text).abs();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
