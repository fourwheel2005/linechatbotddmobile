package com.example.linechatbotddmobile.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThaiProvinceNormalizerTests {

    @Test
    void normalizesLoeiWhenCustomerAnswersProvinceBriefly() {
        assertThat(ThaiProvinceNormalizer.normalize("เลย")).contains("เลย");
        assertThat(ThaiProvinceNormalizer.normalize("เลยค่ะ")).contains("เลย");
        assertThat(ThaiProvinceNormalizer.normalize("จ.เลย")).contains("เลย");
        assertThat(ThaiProvinceNormalizer.normalize("จังหวัดเลย")).contains("เลย");
        assertThat(ThaiProvinceNormalizer.normalize("อยู่เลยครับ")).contains("เลย");
    }

    @Test
    void normalizesCommonProvinceAliases() {
        assertThat(ThaiProvinceNormalizer.normalize("กทม")).contains("กรุงเทพมหานคร");
        assertThat(ThaiProvinceNormalizer.normalize("กรุงเทพค่ะ")).contains("กรุงเทพมหานคร");
        assertThat(ThaiProvinceNormalizer.normalize("โคราช")).contains("นครราชสีมา");
        assertThat(ThaiProvinceNormalizer.normalize("อยุธยา")).contains("พระนครศรีอยุธยา");
    }

    @Test
    void doesNotTreatLoeiInsideUnrelatedSentenceAsProvince() {
        assertThat(ThaiProvinceNormalizer.normalize("ไม่เคยซ่อมเลยครับ")).isEmpty();
        assertThat(ThaiProvinceNormalizer.normalize("ยังไม่รู้เลย")).isEmpty();
    }
}
