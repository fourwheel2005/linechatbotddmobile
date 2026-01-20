package com.example.linechatbotddmobile.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    // สามารถเพิ่ม custom query ได้ถ้าต้องการ
}