package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ResourceBindingLoaderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void absentBindingsRemainCompatibleAndProduceNoAckFailures() throws Exception {
        ResourceBindingLoader.LoadResult result = ResourceBindingLoader.load(mapper.readTree("{\"model\":\"deepseek\"}"));

        assertThat(result.successful()).isTrue();
        assertThat(result.bindings()).isEmpty();
        assertThat(result.acknowledgements()).isEmpty();
        assertThat(result.failureMessage()).isEmpty();
    }

    @Test
    void loadsSupportedBindingsAndNormalizesTypeWithStableSuccessAck() throws Exception {
        ResourceBindingLoader.LoadResult result = ResourceBindingLoader.load(mapper.readTree("""
                {"resourceBindings":[
                  {"type":"skill","reference":"skill-a","revision":"skill-2","digest":"sha256:skill"},
                  {"type":"MCP","reference":"server-a","revision":"mcp-7","digest":"sha256:mcp"}
                ]}
                """));

        assertThat(result.successful()).isTrue();
        assertThat(result.bindings()).extracting(ResourceBindingLoader.ResourceBinding::type)
                .containsExactly("SKILL", "MCP");
        assertThat(result.acknowledgements()).extracting(ResourceBindingLoader.BindingAck::status)
                .containsOnly(ResourceBindingLoader.AckStatus.SUCCESS);
        assertThat(result.failureMessage()).isEmpty();
    }

    @Test
    void validatesEveryRequiredFieldAndReportsStableFailureCodes() throws Exception {
        ResourceBindingLoader.LoadResult result = ResourceBindingLoader.load(mapper.readTree("""
                {"resourceBindings":[
                  {"type":"UNKNOWN","reference":" ","revision":12,"digest":""},
                  {"type":"MODEL","reference":"model-a","revision":"r1","digest":"sha256:model"}
                ]}
                """));

        assertThat(result.successful()).isFalse();
        assertThat(result.acknowledgements()).extracting(ResourceBindingLoader.BindingAck::status)
                .containsExactly(ResourceBindingLoader.AckStatus.FAILED, ResourceBindingLoader.AckStatus.SUCCESS);
        assertThat(result.acknowledgements().get(0).failureCodes())
                .containsExactly("INVALID_REFERENCE", "INVALID_REVISION", "INVALID_DIGEST", "INVALID_TYPE");
        assertThat(result.failureMessage())
                .isEqualTo("RESOURCE_BINDING_INVALID: index:0=INVALID_REFERENCE,INVALID_REVISION,INVALID_DIGEST,INVALID_TYPE");
    }

    @Test
    void rejectsNonArrayBindings() throws Exception {
        ResourceBindingLoader.LoadResult result = ResourceBindingLoader.load(
                mapper.readTree("{\"resourceBindings\":{}}"));

        assertThat(result.successful()).isFalse();
        assertThat(result.failureMessage())
                .isEqualTo("RESOURCE_BINDING_INVALID: manifest=INVALID_COLLECTION");
    }
}
