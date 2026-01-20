package com.example.linechatbotddmobile.product;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // ค้นหาสินค้าจาก keyword
    public List<Product> searchProducts(String keyword) {
        String searchTerm = keyword.toLowerCase();
        return productRepository.findAll().stream()
                .filter(p -> matchesKeyword(p, searchTerm))
                .collect(Collectors.toList());
    }

    private boolean matchesKeyword(Product product, String keyword) {
        return product.getName().toLowerCase().contains(keyword) ||
                product.getCategory().toLowerCase().contains(keyword) ||
                product.getDescription().toLowerCase().contains(keyword) ||
                (product.getKeywords() != null &&
                        product.getKeywords().toLowerCase().contains(keyword));
    }

    // สร้าง knowledge base จากสินค้าทั้งหมด
    public String generateProductKnowledge() {
        List<Product> products = productRepository.findAll();

        StringBuilder knowledge = new StringBuilder();
        knowledge.append("=== สินค้าและราคาในร้าน ===\n\n");

        // จัดกลุ่มตามประเภท
        products.stream()
                .collect(Collectors.groupingBy(Product::getCategory))
                .forEach((category, productList) -> {
                    knowledge.append("**").append(category).append(":**\n");
                    productList.forEach(p -> {
                        knowledge.append("- ").append(p.getName())
                                .append(" - ราคา ").append(String.format("%.0f", p.getPrice()))
                                .append(" บาท");

                        if (p.getColors() != null && !p.getColors().isEmpty()) {
                            knowledge.append(" (มีสี: ").append(p.getColors()).append(")");
                        }

                        if (p.getStorage() != null && !p.getStorage().isEmpty()) {
                            knowledge.append(" ").append(p.getStorage());
                        }

                        if (!p.getInStock()) {
                            knowledge.append(" [สินค้าหมด]");
                        }

                        knowledge.append("\n");
                    });
                    knowledge.append("\n");
                });

        return knowledge.toString();
    }

    public String formatProductInfo(List<Product> products) {
        if (products.isEmpty()) {
            return "ไม่พบสินค้าที่ค้นหา";
        }

        StringBuilder info = new StringBuilder();
        info.append("พบสินค้าดังนี้:\n\n");

        for (Product p : products) {
            info.append("📱 ").append(p.getName()).append("\n");
            info.append("💰 ราคา: ").append(String.format("%.0f", p.getPrice())).append(" บาท\n");

            if (p.getColors() != null && !p.getColors().isEmpty()) {
                info.append("🎨 สี: ").append(p.getColors()).append("\n");
            }

            if (p.getStorage() != null && !p.getStorage().isEmpty()) {
                info.append("💾 ความจุ: ").append(p.getStorage()).append("\n");
            }

            if (p.getDescription() != null && !p.getDescription().isEmpty()) {
                info.append("📝 ").append(p.getDescription()).append("\n");
            }

            info.append(p.getInStock() ? "✅ มีสินค้า" : "❌ สินค้าหมด").append("\n\n");
        }

        return info.toString();
    }
}