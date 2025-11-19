package org.example.java_code.service.impl;

import org.example.java_code.dto.ApiResponse;
import org.example.java_code.dto.GenerationRequest;
import org.example.java_code.service.ApiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ApiServiceImpl implements ApiService {

    private final RestTemplate restTemplate;

    @Value("${api.url}")
    private String apiUrl;

    public ApiServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public ApiResponse generateIntroduction(GenerationRequest request) {
        return restTemplate.postForObject(apiUrl, request, ApiResponse.class);
    }
}

