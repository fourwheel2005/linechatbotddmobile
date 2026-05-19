package com.example.linechatbotddmobile.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IphoneModelPolicyTests {

    @Test
    void detectsUnsupportedModelsWhenCustomerMentionsPhoneContext() {
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Message("มีแต่ไอ 11 จ้า")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Message("ip11")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Message("iphone 11 pro max")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Message("ไอโฟน x")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Message("iphone xr")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Message("iphone se 2020")).isTrue();
    }

    @Test
    void allowsSupportedModelsAndNumbersWithoutPhoneContext() {
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Message("iphone 12")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Message("ไอโฟน 13 pro max")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Message("อายุ 11 ปี")).isFalse();
    }

    @Test
    void detectsUnsupportedExtractedModelsFromFlow() {
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Model("11")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Model("11 Pro Max")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Model("iPhone X")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Model("XR")).isTrue();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Model("12")).isFalse();
        assertThat(IphoneModelPolicy.isUnsupportedBelowIphone12Model("12 Pro")).isFalse();
    }
}
