package com.example.na.order.assistant.support;

import com.example.na.order.catalog.api.ProductInfo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class AssistantTestCatalog {

    private AssistantTestCatalog() {
    }

    public static List<ProductInfo> products() {
        List<ProductInfo> products = new ArrayList<>();
        products.add(keyboard());
        products.add(new ProductInfo(2L, "无线鼠标 M330", "静音无线鼠标，续航 18 个月",
                new BigDecimal("129.00"), 40, true, "外设", "鼠标,鼠标器,无线,mouse,输入设备"));
        products.add(new ProductInfo(3L, "27 英寸显示器", "2K 分辨率 IPS 显示器，75Hz",
                new BigDecimal("1399.00"), 12, true, "显示", "显示器,屏幕,2K,monitor"));
        products.add(new ProductInfo(4L, "USB-C 扩展坞", "七合一扩展坞，支持 HDMI 与千兆网口",
                new BigDecimal("399.00"), 18, true, "外设", "扩展坞,拓展坞,USB-C,转接,dock"));
        products.add(new ProductInfo(5L, "降噪耳麦", "头戴式主动降噪耳麦，带麦克风",
                new BigDecimal("259.00"), 30, true, "音频", "耳麦,耳机,headset,降噪"));
        products.add(new ProductInfo(6L, "人体工学椅", "可调腰托与扶手，网布透气",
                new BigDecimal("1099.00"), 6, true, "家具", "椅子,工学椅,座椅,chair"));
        products.add(new ProductInfo(7L, "桌面氛围灯", "RGB 氛围灯带，支持触控调光",
                new BigDecimal("159.00"), 50, true, "照明", "灯,氛围灯,灯带,rgb"));
        products.add(new ProductInfo(8L, "限量鼠标垫", "800×300mm 加厚鼠标垫，限量库存",
                new BigDecimal("59.00"), 1, true, "外设", "鼠标垫,桌垫,配件"));
        return products;
    }

    public static ProductInfo keyboard() {
        return new ProductInfo(1L, "机械键盘 K87", "87 键热插拔机械键盘，白色背光",
                new BigDecimal("299.00"), 25, true, "外设", "键盘,机械键盘,keyboard,输入设备");
    }

    public static ProductInfo chair() {
        return new ProductInfo(6L, "人体工学椅", "可调腰托与扶手，网布透气",
                new BigDecimal("1099.00"), 6, true, "家具", "椅子,工学椅,座椅,chair");
    }
}
