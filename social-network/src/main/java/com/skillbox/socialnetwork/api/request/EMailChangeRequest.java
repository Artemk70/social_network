package com.skillbox.socialnetwork.api.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class EMailChangeRequest {
    @JsonProperty("email")
    private String eMail;
}
