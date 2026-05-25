package com.nexus.notification.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

public class DeliveryException extends RuntimeException {
    private final String channel;
    private final boolean transient_;
    private boolean endpointInvalid = false;
    private String endpointArn;

    public DeliveryException(String channel, String message,
                             boolean isTransient) {
        super(message);
        this.channel = channel;
        this.transient_ = isTransient;
    }

    public String getChannel() { return channel; }
    public boolean isTransient() { return transient_; }
    public boolean isEndpointInvalid() { return endpointInvalid; }
    public void setEndpointInvalid(boolean v) { endpointInvalid = v; }
    public String getEndpointArn() { return endpointArn; }
    public void setEndpointArn(String arn) { endpointArn = arn; }
}