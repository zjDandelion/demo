package com.example.na.order.catalog.repository;

import com.example.na.order.catalog.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByEnabledTrueOrderByIdAsc();

    Optional<Product> findByIdAndEnabledTrue(Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.stock = p.stock - :quantity "
            + "where p.id = :productId and p.stock >= :quantity and p.enabled = true")
    int deductStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :quantity where p.id = :productId")
    int restoreStock(@Param("productId") Long productId, @Param("quantity") int quantity);
}
