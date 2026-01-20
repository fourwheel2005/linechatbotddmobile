package com.example.linechatbotddmobile.product;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category; // "มือถือ", "อุปกรณ์เสริม", "หูฟัง"

    @Column(nullable = false)
    private Double price;

    @Column(length = 1000)
    private String description;

    private String colors; // "ดำ,ขาว,น้ำเงิน"

    private String storage; // "128GB", "256GB", "512GB"

    private Boolean inStock = true;

    @Column(length = 2000)
    private String keywords; // "iphone,apple,ไอโฟน,15,pro,max"
}