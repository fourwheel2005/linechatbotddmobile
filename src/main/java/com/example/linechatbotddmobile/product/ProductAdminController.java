package com.example.linechatbotddmobile.product;


import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller // ใช้ @Controller ธรรมดา (ไม่ใช่ RestController) เพราะเราจะคืนค่าเป็นหน้าเว็บ
@RequestMapping("/admin")
public class ProductAdminController {

    private final ProductRepository productRepository;

    public ProductAdminController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // 1. หน้าแสดงรายการสินค้าทั้งหมด
    @GetMapping("/products")
    public String listProducts(Model model) {
        model.addAttribute("products", productRepository.findAll());
        return "product-list"; // ส่งไปที่ไฟล์ product-list.html
    }

    // 2. หน้าฟอร์มเพิ่มสินค้าใหม่
    @GetMapping("/products/new")
    public String showAddForm(Model model) {
        Product product = new Product();
        model.addAttribute("product", product);
        return "product-form"; // ส่งไปที่ไฟล์ product-form.html
    }

    // 3. บันทึกสินค้า (ทั้งเพิ่มใหม่ และ แก้ไข)
    @PostMapping("/products/save")
    public String saveProduct(@ModelAttribute("product") Product product) {
        // ถ้าเป็นการแก้ไข ID จะมาด้วย, ถ้าเพิ่มใหม่ ID จะเป็น null
        productRepository.save(product);
        return "redirect:/admin/products"; // บันทึกเสร็จกลับไปหน้าแรก
    }

    // 4. หน้าฟอร์มแก้ไขสินค้า
    @GetMapping("/products/edit/{id}")
    public String showEditForm(@PathVariable("id") Long id, Model model) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid product Id:" + id));
        model.addAttribute("product", product);
        return "product-form"; // ใช้ฟอร์มเดียวกับตอนเพิ่ม
    }

    // 5. ลบสินค้า
    @GetMapping("/products/delete/{id}")
    public String deleteProduct(@PathVariable("id") Long id) {
        productRepository.deleteById(id);
        return "redirect:/admin/products";
    }
}