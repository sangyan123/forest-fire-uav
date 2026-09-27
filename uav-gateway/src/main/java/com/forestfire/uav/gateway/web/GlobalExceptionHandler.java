package com.forestfire.uav.gateway.web;

import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to the unified response envelope.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "Malformed request body"));
    }

    @ExceptionHandler(ValueInstantiationException.class)
    public ResponseEntity<ApiResponse<Void>> valueInstantiation(ValueInstantiationException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
    }

    @ExceptionHandler(MqttException.class)
    public ResponseEntity<ApiResponse<Void>> mqtt(MqttException e) {
        log.error("MQTT operation failed", e);
        return ResponseEntity.internalServerError().body(ApiResponse.error(502, "MQTT operation failed: "
                + e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError().body(ApiResponse.error(500, "Internal error"));
    }
}
