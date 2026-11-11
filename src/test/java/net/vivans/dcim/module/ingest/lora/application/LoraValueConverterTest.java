package net.vivans.dcim.module.ingest.lora.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LoraValueConverterTest {

    private final LoraValueConverter converter = new LoraValueConverter(new ObjectMapper());

    @Test
    void convert_numericValueWithoutScaleOrValueMap() {
        LoraConversionResult result = converter.convert(new DoubleNode(23.5), null, null);

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.OK);
        assertThat(result.value()).isEqualTo(23.5);
    }

    @Test
    void convert_appliesScale() {
        LoraConversionResult result = converter.convert(new DoubleNode(1013.0), 0.1, null);

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.OK);
        assertThat(result.value()).isCloseTo(101.3, within(1e-9));
    }

    @Test
    void convert_temperatureSentinelIsNoDataNotFailure() {
        LoraConversionResult result = converter.convert(new DoubleNode(327.67), null, null);

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.NO_DATA);
        assertThat(result.value()).isNull();
    }

    @Test
    void convert_humiditySentinelIsNoDataNotFailure() {
        LoraConversionResult result = converter.convert(new DoubleNode(409.5), null, null);

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.NO_DATA);
    }

    @Test
    void convert_mapsStatusTextViaValueMapExactMatch() {
        LoraConversionResult result = converter.convert(
                new TextNode("leak"), null, "{\"leak\":1,\"no leak\":0}");

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.OK);
        assertThat(result.value()).isEqualTo(1.0);
    }

    @Test
    void convert_mapsStatusTextViaValueMapCaseInsensitiveMatch() {
        LoraConversionResult result = converter.convert(
                new TextNode("Leak"), null, "{\"leak\":1,\"no leak\":0}");

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.OK);
        assertThat(result.value()).isEqualTo(1.0);
    }

    @Test
    void convert_parsesNumericTextWhenNoValueMap() {
        LoraConversionResult result = converter.convert(new TextNode("42.5"), null, null);

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.OK);
        assertThat(result.value()).isEqualTo(42.5);
    }

    @Test
    void convert_failsWhenNeitherNumericNorInValueMap() {
        LoraConversionResult result = converter.convert(
                new TextNode("unexpected-status"), null, "{\"leak\":1,\"no leak\":0}");

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.FAILED);
    }

    @Test
    void convert_booleanTrueBecomesOne() {
        LoraConversionResult result = converter.convert(BooleanNode.TRUE, null, null);

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.OK);
        assertThat(result.value()).isEqualTo(1.0);
    }

    @Test
    void convert_booleanFalseBecomesZero() {
        LoraConversionResult result = converter.convert(BooleanNode.FALSE, null, null);

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.OK);
        assertThat(result.value()).isEqualTo(0.0);
    }

    @Test
    void convert_nullRawValueIsNoData() {
        LoraConversionResult result = converter.convert(null, null, null);

        assertThat(result.status()).isEqualTo(LoraConversionResult.Status.NO_DATA);
    }
}
