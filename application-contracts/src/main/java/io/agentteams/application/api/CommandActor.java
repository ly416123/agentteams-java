package io.agentteams.application.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Authenticated principal and ingress channel carried by a command or event. */
public record CommandActor(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("channel") String channel) {

    public CommandActor {
        ProtocolValidation.requireText(type, "actor.type");
        ProtocolValidation.requireText(id, "actor.id");
        ProtocolValidation.requireText(channel, "actor.channel");
    }
}
