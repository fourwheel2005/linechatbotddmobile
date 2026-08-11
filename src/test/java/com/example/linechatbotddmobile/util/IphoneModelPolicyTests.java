package com.example.linechatbotddmobile.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IphoneModelPolicyTests {

    @Test
    void detectsUnsupportedModelsWhenCustomerMentionsPhoneContext() {
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("มีแต่ไอ 11 จ้า")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("ip11")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("iphone 11 pro max")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("ไอโฟน x")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("iphone xr")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("iphone se 2020")).isTrue();

        // ตารางราคาชุดใหม่ตัดรุ่น 12 ออก → ร้านเลิกรับ 12 ทุกรุ่นย่อย
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("iphone 12")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("ไอโฟน 12 pro max")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("ip12 mini")).isTrue();
    }

    @Test
    void allowsSupportedModelsAndNumbersWithoutPhoneContext() {
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("ไอโฟน 13 pro max")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("iphone 13 mini")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("ไอโฟน 17 pro")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Message("อายุ 11 ปี")).isFalse();
    }

    @Test
    void detectsUnsupportedExtractedModelsFromFlow() {
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("11")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("11 Pro Max")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("iPhone X")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("XR")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("12")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("12 Pro")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("12 Pro Max")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("12 mini")).isTrue();
    }

    @Test
    void acceptsEveryModelThatExistsInThePriceTable() {
        // 13 mini คือรุ่นเล็กสุดที่รับ — ระวังอย่าให้ "13" ไปติดกฎของ "1" หรือ "12"
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("13 mini")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("13")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("13 Pro Max")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("14 Plus")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("16e")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("17e")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("17 Air")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone13Model("17 Pro Max")).isFalse();
    }
}
