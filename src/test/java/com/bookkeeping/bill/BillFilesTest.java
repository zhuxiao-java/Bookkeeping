package com.bookkeeping.bill;

import com.bookkeeping.constant.CategoryType;
import com.bookkeeping.constant.TransactionType;
import com.bookkeeping.dao.dto.CategoryDTO;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账单解码、表头识别和分类别名。不碰数据库。
 */
class BillFilesTest {

    private static final String WECHAT_HEADER =
            "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注";

    @Test
    void excelSerialAndPartialRefundAndPhoneTopUp() throws Exception {
        String csv = ""
                + "说明行\n"
                + WECHAT_HEADER + "\n"
                + "45352.5,商户消费,店,货,支出,1.00,零钱,支付成功,99,m,\n"
                + "2024-03-07 13:00:00,退款,美团,外卖退款,收入,5.00,零钱,退款成功,100,m2,\n";
        PaymentBillParser.BillDocument wechat = PaymentBillParser.tryParse(
                BillFiles.read(csv.getBytes(StandardCharsets.UTF_8), "a.csv"));
        assertEquals(LocalDateTime.of(2024, 3, 1, 12, 0), wechat.entries().get(0).date());
        assertEquals(TransactionType.EXPENSE, wechat.entries().get(0).type());
        assertEquals(TransactionType.INCOME, wechat.entries().get(1).type());
        assertEquals("data", wechat.entries().get(1).kind());

        String alipay = ""
                + "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注\n"
                + "2024-04-02 10:00:00,充值缴费,移动,10086,话费充值,支出,50.00,余额,交易成功,200,b,\n";
        PaymentBillParser.BillDocument phone = PaymentBillParser.tryParse(
                BillFiles.read(alipay.getBytes(StandardCharsets.UTF_8), "alipay.csv"));
        assertEquals(TransactionType.EXPENSE, phone.entries().get(0).type());
        assertEquals("充值缴费", phone.entries().get(0).categoryHint());
        assertEquals("支付宝", phone.entries().get(0).accountName());
    }

    @Test
    void sharedStringsKeepColumnGaps() throws Exception {
        String shared = "<sst><si><t>交易时间</t></si><si><t>金额</t></si></sst>";
        String sheet = """
                <worksheet><sheetData>
                <row r="1">
                <c r="A1" t="s"><v>0</v></c>
                <c r="C1" t="s"><v>1</v></c>
                </row>
                <row r="3"><c r="A3" t="inlineStr"><is><t>尾</t></is></c></row>
                </sheetData></worksheet>
                """;
        byte[] bytes = xlsx(sheet, shared);
        List<List<String>> grid = BillFiles.read(bytes, "a.xlsx");
        assertEquals("交易时间", grid.get(0).get(0));
        assertEquals("", grid.get(0).get(1));
        assertEquals("金额", grid.get(0).get(2));
        assertEquals("尾", grid.get(2).get(0));
    }

    @Test
    void aliasUsesExistingNameOtherwiseFallback() {
        Map<String, CategoryDTO> categories = new HashMap<>();
        categories.put("餐饮", category("餐饮", CategoryType.EXPENSE));
        categories.put("红包", category("红包", CategoryType.INCOME));
        categories.put("红包支出", category("红包支出", CategoryType.EXPENSE));

        CategoryAliases.Match food = CategoryAliases.resolve("餐饮美食", CategoryType.EXPENSE, categories);
        assertEquals("餐饮", food.name());
        assertFalse(food.fallback());

        CategoryAliases.Match unmatched = CategoryAliases.resolve("商户消费", CategoryType.EXPENSE, Map.of());
        assertEquals("待整理", unmatched.name());
        assertTrue(unmatched.fallback());

        assertEquals("红包支出", CategoryAliases.resolve("微信红包", CategoryType.EXPENSE, categories).name());
        assertEquals("红包", CategoryAliases.resolve("红包", CategoryType.INCOME, categories).name());
        assertEquals("待整理收入", CategoryAliases.resolve("其他收入", CategoryType.INCOME, categories).name());
        assertNull(PaymentBillParser.tryParse(List.of(List.of("日期", "类型", "金额"))));
    }

    private static byte[] xlsx(String sheetXml, String sharedXml) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheetXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write(sharedXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    private static CategoryDTO category(String name, CategoryType type) {
        CategoryDTO dto = new CategoryDTO();
        dto.setName(name);
        dto.setType(type);
        return dto;
    }
}
