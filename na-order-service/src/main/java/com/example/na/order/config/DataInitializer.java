package com.example.na.order.config;

import com.example.na.order.user.domain.User;
import com.example.na.order.user.repository.UserRepository;
import com.example.na.order.catalog.domain.Product;
import com.example.na.order.catalog.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DemoUserProperties demoUserProperties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (productRepository.count() == 0) {
            productRepository.saveAll(buildProducts());
            log.info("Seeded {} products", productRepository.count());
        } else {
            log.info("Product seed skipped, existing data found");
        }

        if (userRepository.count() == 0) {
            User demo = new User(
                    demoUserProperties.getUsername(),
                    passwordEncoder.encode(demoUserProperties.getPassword()),
                    demoUserProperties.getDisplayName());
            userRepository.save(demo);
            log.info("Seeded demo user '{}'", demoUserProperties.getUsername());
        } else {
            log.info("User seed skipped, existing data found");
        }
    }

    private List<Product> buildProducts() {
        List<Product> products = new ArrayList<>();
        products.add(new Product("机械键盘 K87", "87 键热插拔机械键盘，白色背光", new BigDecimal("299.00"), 25, true));
        products.add(new Product("无线鼠标 M330", "静音无线鼠标，续航 18 个月", new BigDecimal("129.00"), 40, true));
        products.add(new Product("27 英寸显示器", "2K 分辨率 IPS 显示器，75Hz", new BigDecimal("1399.00"), 12, true));
        products.add(new Product("USB-C 扩展坞", "七合一扩展坞，支持 HDMI 与千兆网口", new BigDecimal("399.00"), 18, true));
        products.add(new Product("降噪耳麦", "头戴式主动降噪耳麦，带麦克风", new BigDecimal("259.00"), 30, true));
        products.add(new Product("人体工学椅", "可调腰托与扶手，网布透气", new BigDecimal("1099.00"), 6, true));
        products.add(new Product("桌面氛围灯", "RGB 氛围灯带，支持触控调光", new BigDecimal("159.00"), 50, true));
        products.add(new Product("限量鼠标垫", "800×300mm 加厚鼠标垫，限量库存", new BigDecimal("59.00"), 1, true));
        return products;
    }
}
